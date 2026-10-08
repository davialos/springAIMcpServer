package com.springaimcpservercommon.loadtest.k6;

import com.springaimcpservercommon.loadtest.data.DataPlan;
import com.springaimcpservercommon.loadtest.data.FieldPlan;
import com.springaimcpservercommon.loadtest.data.PoolRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Human-readable answer to "which API inputs will carry real data, from where, and which will not": the suite's
 * {@code data/bindings.md}. Identifier inputs (ids, foreign keys, natural keys) with no usable real values are the
 * ones that make a real run answer 404/422, so they are listed first with the ways to fix them.
 */
public final class BindingReport {

    /**
     * Counts for the generation log.
     *
     * @param bound              inputs bound to a pool that has values (or is filled by seeding)
     * @param boundWithoutValues inputs bound to a pool that came back empty
     * @param unboundIdentifiers identifier inputs with no pool at all
     */
    public record Summary(int bound, int boundWithoutValues, int unboundIdentifiers) {
    }

    private BindingReport() {
    }

    /**
     * Counts how many inputs carry real data.
     *
     * @param plan   data plan
     * @param pools  pool key → sampled values
     * @param seeded pool keys filled by seeding at run time
     * @return the counts
     */
    public static Summary summarize(DataPlan plan, Map<String, List<Object>> pools, Set<String> seeded) {
        int bound = 0;
        int empty = 0;
        int unbound = 0;
        for (FieldPlan f : plan.fields().values()) {
            if (f.pool() != null) {
                if (hasValues(f.pool(), pools, seeded)) {
                    bound++;
                } else {
                    empty++;
                }
            } else if (f.kind().identifier() && !f.sensitive()) {
                unbound++;
            }
        }
        return new Summary(bound, empty, unbound);
    }

    static String render(DataPlan plan, Map<String, List<Object>> pools, Set<String> seeded) {
        Summary s = summarize(plan, pools, seeded);
        StringBuilder md = new StringBuilder("# Real-data bindings\n\n");
        md.append("Generated with the suite (`data/plan.json` has the same facts as JSON). ")
                .append(s.bound()).append(" inputs draw real values, ")
                .append(s.boundWithoutValues()).append(" are bound to a pool with no values, ")
                .append(s.unboundIdentifiers()).append(" identifier inputs have no real data.\n\n");

        List<FieldPlan> missing = new ArrayList<>();
        for (FieldPlan f : plan.fields().values()) {
            if (f.pool() == null ? f.kind().identifier() && !f.sensitive() : !hasValues(f.pool(), pools, seeded)) {
                missing.add(f);
            }
        }
        if (!missing.isEmpty()) {
            md.append("## Inputs that will not hit existing rows\n\n")
                    .append("Their requests fall back to generated values, so lookups answer 404 and creates may ")
                    .append("fail on foreign keys. Fix with `--bind '<field>=<table>.<column>'`, a query pool ")
                    .append("(`--bind '<field>=sql:SELECT id FROM …'`), `data/user.json` → `fields`, or seeding.\n\n")
                    .append("| Field | Kind | Why |\n|---|---|---|\n");
            for (FieldPlan f : missing) {
                md.append("| `").append(f.key()).append("` | ").append(f.kind().generator()).append(" | ")
                        .append(f.pool() == null ? "no table or column matched" : "`" + f.pool().key()
                                + "` returned no values").append(" |\n");
            }
            md.append('\n');
        }

        md.append("## Inputs bound to real data\n\n| Field | Source | Values |\n|---|---|---|\n");
        boolean any = false;
        for (FieldPlan f : plan.fields().values()) {
            PoolRef p = f.pool();
            if (p == null || !hasValues(p, pools, seeded)) {
                continue;
            }
            any = true;
            boolean created = seeded.contains(p.key());
            int n = pools.getOrDefault(p.key(), List.of()).size();
            md.append("| `").append(f.key()).append("` | ").append(describe(p)).append(created
                    ? "; plus rows created in setup" : "").append(" | ").append(n == 0 ? "setup" : n)
                    .append(" |\n");
        }
        if (!any) {
            md.append("| _none_ | | |\n");
        }
        md.append("\nPick strategy (`data.pick` / `PICK`): `random`, `partition` (one slice per VU) or `sequence`. ")
                .append("Values of one table that were sampled together come from the same row within a request.\n");
        return md.toString();
    }

    private static boolean hasValues(PoolRef pool, Map<String, List<Object>> pools, Set<String> seeded) {
        return !pools.getOrDefault(pool.key(), List.of()).isEmpty() || seeded.contains(pool.key());
    }

    private static String describe(PoolRef p) {
        return p.isQuery() ? "query `" + p.key() + "` — `" + p.sql().replace("|", "\\|") + "`"
                : "column `" + p.key() + "`";
    }
}
