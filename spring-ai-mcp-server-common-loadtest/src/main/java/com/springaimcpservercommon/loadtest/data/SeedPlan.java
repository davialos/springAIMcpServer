package com.springaimcpservercommon.loadtest.data;

import com.springaimcpservercommon.loadtest.discovery.Documents;
import com.springaimcpservercommon.loadtest.model.ApiCatalog;
import com.springaimcpservercommon.loadtest.model.ApiEndpoint;
import com.springaimcpservercommon.loadtest.model.ApiParam;
import com.springaimcpservercommon.loadtest.model.ArraySchema;
import com.springaimcpservercommon.loadtest.model.HttpMethod;
import com.springaimcpservercommon.loadtest.model.ObjectSchema;
import com.springaimcpservercommon.loadtest.model.ParamLocation;
import com.springaimcpservercommon.loadtest.model.RefSchema;
import com.springaimcpservercommon.loadtest.model.Schema;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * How to create test data through the application's own create endpoints, in the order the entity relationships
 * demand: a table is seeded after every table its create payload references (a {@code Contact} needs a
 * {@code Company}, a {@code Deal} needs a {@code Contact} and a {@code User}) — whether the reference travels in
 * the payload or the server fills it in (an article's author is the logged-in user). Ids the server returns are
 * captured and fed into the payloads of the next level and into the load test itself, so foreign keys always
 * point at rows that exist. Writes go through the application (validation, events, auditing apply), never
 * straight to the database.
 *
 * @param steps seed steps, parents before children
 */
public record SeedPlan(List<Step> steps) {

    /** Compact constructor: defensive copy. */
    public SeedPlan {
        steps = List.copyOf(steps);
    }

    /**
     * One table to seed.
     *
     * @param api           create endpoint id
     * @param table         table the endpoint creates rows in
     * @param pool          pool key of the table's primary key ({@code companies.id}): captured ids go there
     * @param idField       property holding the id in the response (or request, for natural keys)
     * @param idFromRequest the id is chosen by the client (natural key such as a SKU), read it from the request
     * @param dependsOn     pool keys of the tables this create references (seeded first when they have a step;
     *                      then it is that step's {@code pool}, whichever column of the table is referenced)
     * @param deleteApi     endpoint deleting one row, for optional cleanup; {@code null} if none
     * @param captures      other columns of this table that requests address rows by, pool key → property
     *                      ({@code articles.slug → slug} for {@code /articles/{slug}}), read from the response
     *                      or else the request of each create
     * @param deletePool    pool whose values {@code deleteApi} takes ({@code pool}, or one of {@code captures})
     */
    public record Step(String api, String table, String pool, String idField, boolean idFromRequest,
                       List<String> dependsOn, @Nullable String deleteApi, Map<String, String> captures,
                       String deletePool) {

        /** Compact constructor: defensive copies. */
        public Step {
            dependsOn = List.copyOf(dependsOn);
            captures = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(captures));
        }

        /**
         * A step that captures only the primary key.
         *
         * @param api           create endpoint id
         * @param table         table
         * @param pool          primary-key pool
         * @param idField       id property
         * @param idFromRequest natural key chosen by the client
         * @param dependsOn     parent pools
         * @param deleteApi     delete endpoint, or {@code null}
         */
        public Step(String api, String table, String pool, String idField, boolean idFromRequest,
                    List<String> dependsOn, @Nullable String deleteApi) {
            this(api, table, pool, idField, idFromRequest, dependsOn, deleteApi, Map.of(), pool);
        }
    }

    /**
     * Plans seeding for a catalog.
     *
     * @param catalog discovered APIs
     * @param plan    data plan (fields bound to tables)
     * @param index   table index
     * @param log     receives notes (ignored creates, cycles)
     * @return the plan; empty when no create endpoint maps to a table
     */
    public static SeedPlan build(ApiCatalog catalog, DataPlan plan, TableIndex index, Consumer<String> log) {
        Map<String, Step> byPool = new LinkedHashMap<>();
        Map<String, ApiEndpoint> chosen = new LinkedHashMap<>();
        Map<String, TableIndex.TableRef> tables = new LinkedHashMap<>();
        for (ApiEndpoint e : catalog.endpoints()) {
            if (e.method() != HttpMethod.POST || e.body() == null) {
                continue;
            }
            Optional<TableIndex.TableRef> table = target(e, index);
            if (table.isEmpty() || table.get().idColumn() == null) {
                continue;
            }
            TableIndex.TableRef t = table.get();
            String pool = new PoolRef(t.schema(), t.table(), t.idColumn()).key();
            ApiEndpoint previous = chosen.get(pool);
            if (previous != null && preference(previous) <= preference(e)) {
                continue; // several creates for one table: keep the plainest (fewest path params, not Data REST)
            }
            chosen.put(pool, e);
            tables.put(pool, t);
        }
        // pools of each seeded table that some request draws from: id first, then natural keys (slug, username)
        Map<String, String> stepOfTable = new LinkedHashMap<>();
        chosen.forEach((pool, e) -> stepOfTable.put(tableKey(tables.get(pool).schema(), tables.get(pool).table()),
                pool));
        Map<String, Map<String, String>> captures = new LinkedHashMap<>();
        for (PoolRef p : plan.pools()) {
            String step = stepOfTable.get(tableKey(p.schema(), p.table()));
            if (step != null && !p.key().equals(step)) {
                captures.computeIfAbsent(step, k -> new LinkedHashMap<>())
                        .put(p.key(), property(tables.get(step), p.column()));
            }
        }
        for (var entry : chosen.entrySet()) {
            String pool = entry.getKey();
            ApiEndpoint e = entry.getValue();
            TableIndex.TableRef t = tables.get(pool);
            Set<String> deps = new LinkedHashSet<>();
            for (FieldPlan f : fieldsOf(e, catalog, plan)) {
                if (f.pool() != null) {
                    String parent = stepOfTable.getOrDefault(tableKey(f.pool().schema(), f.pool().table()),
                            f.pool().key());
                    if (!parent.equals(pool)) {
                        deps.add(parent);
                    }
                }
            }
            // the table's own relationships too: a parent the server fills in (the author from the logged-in
            // user) is not in the payload but must still exist first
            List<PoolRef> parents = new ArrayList<>();
            if (t.db() != null) {
                parents.addAll(t.db().foreignKeys().values());
            }
            if (t.entity() != null) {
                for (String target : t.entity().fieldReferences().values()) {
                    index.resolve(target).filter(r -> r.idColumn() != null)
                            .ifPresent(r -> parents.add(new PoolRef(r.schema(), r.table(), r.idColumn())));
                }
            }
            for (PoolRef p : parents) {
                String parent = stepOfTable.get(tableKey(p.schema(), p.table()));
                if (parent != null && !parent.equals(pool)) {
                    deps.add(parent);
                }
            }
            boolean generated = t.entity() == null || t.entity().idGenerated();
            String idField = t.entity() != null && t.entity().idField() != null ? t.entity().idField() : "id";
            Map<String, String> captured = captures.getOrDefault(pool, Map.of());
            String deleteApi = null;
            String deletePool = pool;
            for (String candidate : concat(pool, captured.keySet())) {
                deleteApi = deleteApi(catalog, plan, candidate);
                if (deleteApi != null) {
                    deletePool = candidate;
                    break;
                }
            }
            byPool.put(pool, new Step(e.id(), t.table(), pool, idField, !generated, new ArrayList<>(deps),
                    deleteApi, captured, deletePool));
        }
        return new SeedPlan(order(byPool, log));
    }

    private static String tableKey(@Nullable String schema, String table) {
        return ((schema == null ? "" : schema + ".") + table).toLowerCase(java.util.Locale.ROOT);
    }

    private static List<String> concat(String first, java.util.Collection<String> rest) {
        List<String> out = new ArrayList<>();
        out.add(first);
        out.addAll(rest);
        return out;
    }

    /** The JSON property of a column: the entity field mapped to it, else the column in camelCase. */
    private static String property(TableIndex.TableRef t, String column) {
        if (t.entity() != null) {
            for (var e : t.entity().fieldColumns().entrySet()) {
                if (e.getValue().equalsIgnoreCase(column)) {
                    return e.getKey();
                }
            }
        }
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : column.toCharArray()) {
            if (c == '_' || c == '-') {
                upper = out.length() > 0;
            } else {
                out.append(upper ? Character.toUpperCase(c) : out.isEmpty() ? Character.toLowerCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }

    private static int preference(ApiEndpoint e) {
        return e.params(ParamLocation.PATH).size() * 10 + (e.sources().contains("data-rest") ? 5 : 0)
                + e.path().length() / 100;
    }

    /** The table a create endpoint writes to: its resource entity, its collection segment, or its body DTO. */
    private static Optional<TableIndex.TableRef> target(ApiEndpoint e, TableIndex index) {
        Optional<TableIndex.TableRef> t = index.resolve(e.resource());
        if (t.isEmpty()) {
            t = index.resolve(ApiHarvester.lastSegment(e.path()));
        }
        if (t.isEmpty() && e.body() instanceof RefSchema(String name)) {
            t = index.resolve(DataPlan.schemaResource(name));
        }
        return t;
    }

    private static @Nullable String deleteApi(ApiCatalog catalog, DataPlan plan, String pool) {
        for (ApiEndpoint e : catalog.endpoints()) {
            List<ApiParam> path = e.params(ParamLocation.PATH);
            if (e.method() == HttpMethod.DELETE && path.size() == 1) {
                FieldPlan f = plan.field(FieldKeys.param(e.id(), ParamLocation.PATH, path.getFirst().name()));
                if (f != null && f.pool() != null && f.pool().key().equals(pool)) {
                    return e.id();
                }
            }
        }
        return null;
    }

    /** Every planned field a request to the endpoint can carry: its parameters and its body, through DTOs. */
    static List<FieldPlan> fieldsOf(ApiEndpoint e, ApiCatalog catalog, DataPlan plan) {
        Set<String> owners = new HashSet<>();
        owners.add(e.id());
        if (e.body() != null) {
            collectRefs(e.body(), catalog, owners);
        }
        List<FieldPlan> out = new ArrayList<>();
        for (FieldPlan f : plan.fields().values()) {
            if (owners.contains(f.owner())) {
                out.add(f);
            }
        }
        return out;
    }

    private static void collectRefs(Schema s, ApiCatalog catalog, Set<String> owners) {
        switch (s) {
            case RefSchema(String name) -> {
                if (owners.add(name) && catalog.schemas().containsKey(name)) {
                    collectRefs(catalog.schemas().get(name), catalog, owners);
                }
            }
            case ArraySchema arr -> collectRefs(arr.items(), catalog, owners);
            case ObjectSchema o -> o.properties().values().forEach(p -> collectRefs(p.schema(), catalog, owners));
            default -> { }
        }
    }

    /** Parents first (Kahn); a dependency without its own step is satisfied by sampled data; cycles are broken. */
    private static List<Step> order(Map<String, Step> byPool, Consumer<String> log) {
        List<Step> out = new ArrayList<>();
        Set<String> done = new HashSet<>();
        List<Step> pending = new ArrayList<>(byPool.values());
        pending.sort(Comparator.comparing(Step::table));
        while (!pending.isEmpty()) {
            Step next = null;
            for (Step s : pending) {
                if (s.dependsOn().stream().allMatch(d -> done.contains(d) || !byPool.containsKey(d))) {
                    next = s;
                    break;
                }
            }
            if (next == null) {
                next = pending.getFirst();
                log.accept("seed: dependency cycle around " + next.table() + "; it is seeded with whatever ids of "
                        + next.dependsOn() + " already exist");
            }
            pending.remove(next);
            done.add(next.pool());
            out.add(next);
        }
        return out;
    }

    /**
     * Every pool seeding fills: the primary keys of the seeded tables and their captured natural keys.
     *
     * @return pool keys
     */
    public Set<String> pools() {
        Set<String> out = new LinkedHashSet<>();
        for (Step s : steps) {
            out.add(s.pool());
            out.addAll(s.captures().keySet());
        }
        return out;
    }

    /**
     * Renders {@code data/seed.json}.
     *
     * @return JSON array of steps
     */
    public ArrayNode toJson() {
        ArrayNode arr = Documents.json().createArrayNode();
        for (Step s : steps) {
            ObjectNode n = arr.addObject();
            n.put("api", s.api());
            n.put("table", s.table());
            n.put("pool", s.pool());
            n.put("idField", s.idField());
            n.put("idFromRequest", s.idFromRequest());
            ArrayNode deps = n.putArray("dependsOn");
            s.dependsOn().forEach(deps::add);
            if (s.deleteApi() != null) {
                n.put("deleteApi", s.deleteApi());
                n.put("deletePool", s.deletePool());
            }
            if (!s.captures().isEmpty()) {
                ObjectNode c = n.putObject("captures");
                s.captures().forEach(c::put);
            }
        }
        return arr;
    }
}
