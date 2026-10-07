package com.springaimcpservercommon.loadtest.data;

import com.springaimcpservercommon.loadtest.model.ApiCatalog;
import org.jspecify.annotations.Nullable;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Fills the plan's real-data pools and checks user-supplied values against the database:
 * <ol>
 *   <li>sample each pool's column from the database (when one is configured);</li>
 *   <li>harvest the pools still empty from the running API's collection endpoints (when a base URL is
 *       reachable), checking harvested values in the database when possible;</li>
 *   <li>verify user values of fields bound to a pool: report how many exist, optionally drop the others.</li>
 * </ol>
 */
public final class RealDataCollector {

    /**
     * Outcome.
     *
     * @param pools pool key → values
     * @param user  user data, minus unverified values when dropping was requested
     */
    public record Result(Map<String, List<Object>> pools, UserData user) {
    }

    private final Consumer<String> log;
    private final Set<String> seeded;

    /**
     * Creates a collector.
     *
     * @param log receives one line per pool and per verification
     */
    public RealDataCollector(Consumer<String> log) {
        this(log, Set.of());
    }

    /**
     * Creates a collector for a suite that seeds data.
     *
     * @param log    receives one line per pool and per verification
     * @param seeded pool keys the suite's seeding fills at run time ({@link SeedPlan#pools()})
     */
    public RealDataCollector(Consumer<String> log, Set<String> seeded) {
        this.log = log;
        this.seeded = Set.copyOf(seeded);
    }

    /**
     * Collects real data.
     *
     * @param catalog        discovered APIs (for harvesting)
     * @param plan           data plan (which pools are needed)
     * @param index          table index
     * @param db             database access, or {@code null}
     * @param harvester      API harvester, or {@code null}
     * @param user           user data to verify
     * @param limit          maximum values per pool
     * @param dropUnverified remove user values of bound fields that do not exist in the database
     * @return pools and (possibly filtered) user data
     */
    public Result collect(ApiCatalog catalog, DataPlan plan, TableIndex index, @Nullable DatabaseSampler db,
                          @Nullable ApiHarvester harvester, UserData user, int limit, boolean dropUnverified) {
        Map<String, List<Object>> pools = new LinkedHashMap<>();
        List<PoolRef> missing = new ArrayList<>();
        Set<String> sampledAsRows = db == null ? Set.of() : sampleTablesAsRows(plan, db, limit, pools);
        for (PoolRef pool : plan.pools()) {
            if (sampledAsRows.contains(pool.key())) {
                continue;
            }
            List<Object> values = List.of();
            if (db != null && db.has(pool)) {
                try {
                    values = db.sample(pool, limit);
                    log.accept("real: " + pool.key() + " <- database (" + values.size() + " values)");
                } catch (SQLException e) {
                    log.accept("real: " + pool.key() + " sampling failed: " + e.getMessage());
                }
            } else if (db != null) {
                log.accept("real: " + pool.key() + " is not in the database metadata, skipped");
            }
            if (values.isEmpty()) {
                missing.add(pool);
            } else {
                pools.put(pool.key(), values);
            }
        }
        if (harvester != null && !missing.isEmpty()) {
            for (Map.Entry<String, List<Object>> h : harvester.harvest(catalog, index, missing, limit).entrySet()) {
                List<Object> values = h.getValue();
                if (db != null) {
                    PoolRef ref = missing.stream().filter(p -> p.key().equals(h.getKey())).findFirst().orElseThrow();
                    if (db.has(ref)) {
                        try {
                            values = db.existing(ref, values);
                            log.accept("real: " + h.getKey() + " harvested values checked in database ("
                                    + values.size() + "/" + h.getValue().size() + " exist)");
                        } catch (SQLException e) {
                            log.accept("real: " + h.getKey() + " check failed: " + e.getMessage());
                        }
                    }
                }
                if (!values.isEmpty()) {
                    pools.put(h.getKey(), values);
                }
            }
        }
        for (PoolRef p : plan.pools()) {
            if (!pools.containsKey(p.key()) && seeded.contains(p.key())) {
                log.accept("real: " + p.key() + " is filled at run time by seeding (rows created in setup)");
            } else if (!pools.containsKey(p.key())) {
                log.accept("real: " + p.key() + " has no values; fields bound to it fall back to user/dummy data");
            }
        }
        return new Result(pools, db == null ? user : verifyUser(plan, db, user, dropUnverified));
    }

    /**
     * Pools of one table that the plan binds more than once are sampled together, as whole rows, so the values at
     * the same index belong to the same row ({@code data.js} then uses one index per request for all of the
     * table's fields). A table with no complete row, or a failing query, falls back to per-column sampling.
     *
     * @return keys of the pools filled here
     */
    private Set<String> sampleTablesAsRows(DataPlan plan, DatabaseSampler db, int limit,
                                           Map<String, List<Object>> pools) {
        Map<String, List<PoolRef>> byTable = new LinkedHashMap<>();
        for (PoolRef pool : plan.pools()) {
            String table = pool.tableKeyOrNull();
            if (table != null && db.has(pool)) {
                byTable.computeIfAbsent(table, k -> new ArrayList<>()).add(pool);
            }
        }
        Set<String> done = new HashSet<>();
        for (Map.Entry<String, List<PoolRef>> t : byTable.entrySet()) {
            if (t.getValue().size() < 2) {
                continue;
            }
            try {
                Map<String, List<Object>> rows = db.sampleRows(t.getValue(), limit);
                if (rows.isEmpty()) {
                    continue;
                }
                pools.putAll(rows);
                done.addAll(rows.keySet());
                log.accept("real: " + t.getKey() + " <- database, " + rows.values().iterator().next().size()
                        + " rows sampled together for " + rows.keySet());
            } catch (SQLException | RuntimeException e) {
                log.accept("real: " + t.getKey() + " row sampling failed (" + e.getMessage()
                        + "), sampling columns separately");
            }
        }
        return done;
    }

    private UserData verifyUser(DataPlan plan, DatabaseSampler db, UserData user, boolean drop) {
        UserData out = user;
        for (Map.Entry<String, List<Object>> e : user.fields().entrySet()) {
            PoolRef pool = poolFor(plan, e.getKey());
            if (pool == null || !db.has(pool) || e.getValue().isEmpty()) {
                continue;
            }
            try {
                List<Object> existing = db.existing(pool, e.getValue());
                log.accept("user: " + e.getKey() + " -> " + existing.size() + "/" + e.getValue().size()
                        + " values exist in " + pool.key()
                        + (drop && existing.size() < e.getValue().size() ? " (others dropped)" : ""));
                if (drop) {
                    out = out.withField(e.getKey(), existing);
                }
            } catch (SQLException ex) {
                log.accept("user: " + e.getKey() + " check failed: " + ex.getMessage());
            }
        }
        return out;
    }

    /**
     * The pool of the field(s) a user key addresses: exact key, {@code owner.name} or bare {@code name}. A key that
     * also reaches fields without that pool (e.g. a bare {@code email} used by a search filter and by a create
     * body) is ambiguous and not verified, so valid new values are never dropped.
     */
    private static @Nullable PoolRef poolFor(DataPlan plan, String userKey) {
        FieldPlan exact = plan.field(userKey);
        if (exact != null) {
            return exact.pool();
        }
        PoolRef found = null;
        for (FieldPlan f : plan.fields().values()) {
            if (userKey.equals(f.owner() + "." + f.name()) || userKey.equals(f.name())) {
                if (f.pool() == null || (found != null && !found.equals(f.pool()))) {
                    return null;
                }
                found = f.pool();
            }
        }
        return found;
    }
}
