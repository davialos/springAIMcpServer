package com.springaimcpservercommon.loadtest.data;

import com.springaimcpservercommon.loadtest.discovery.Documents;
import com.springaimcpservercommon.loadtest.model.ApiCatalog;
import com.springaimcpservercommon.loadtest.model.ApiEndpoint;
import com.springaimcpservercommon.loadtest.model.HttpMethod;
import com.springaimcpservercommon.loadtest.model.ObjectSchema;
import com.springaimcpservercommon.loadtest.model.ParamLocation;
import com.springaimcpservercommon.loadtest.model.Property;
import com.springaimcpservercommon.loadtest.model.RefSchema;
import com.springaimcpservercommon.loadtest.model.ScalarSchema;
import com.springaimcpservercommon.loadtest.model.Schema;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Business flows generated from the code: for every resource the application can create, one walk through its life —
 * create → read → update → status transitions → actions → delete — with the id (or natural key) the create returned
 * fed into every later step. It is what a user does with a row, as opposed to the per-API load that hits one endpoint at
 * a time, and it exercises exactly the paths that break under concurrency (state changes of rows other requests read).
 * <p>
 * Everything comes from the discovered endpoints and the seeding plan: item routes are the endpoints whose single path
 * parameter is bound to the created table's key; a status transition is an enum property named like a status in the
 * update body (each value after the first becomes an update with that value, followed by a read); an action is a
 * {@code POST}/{@code PUT} on {@code <collection>/{id}/<verb>} ({@code /orders/{id}/ship}). The flows use the
 * {@code data/journey.json} step format, so {@code lifecycle-<profile>} modes replay them like a recorded journey.
 *
 * @param flows one per resource that has a create endpoint and at least one more step
 */
public record LifecyclePlan(List<Flow> flows) {

    private static final Pattern STATUS_NAME = Pattern.compile("(?i)(status|state|stage|phase|lifecycle)");
    private static final int MAX_TRANSITIONS = 6;

    /** Compact constructor: defensive copy. */
    public LifecyclePlan {
        flows = List.copyOf(flows);
    }

    /**
     * One resource's walk.
     *
     * @param name     flow name ({@code orders})
     * @param resource the table the create endpoint writes to
     * @param steps    journey steps (see {@code lib/http.js}: {@code api, path, query, headers, set, ownRow, pauseMs})
     */
    public record Flow(String name, String resource, ArrayNode steps) {
    }

    /**
     * Builds the flows.
     *
     * @param catalog the APIs
     * @param plan    the data plan (which path parameter addresses which table)
     * @param seed    the seeding plan (create endpoints and how their ids come back)
     * @return the flows; empty when no resource has more than a create
     */
    public static LifecyclePlan build(ApiCatalog catalog, DataPlan plan, SeedPlan seed) {
        List<Flow> flows = new ArrayList<>();
        for (SeedPlan.Step s : seed.steps()) {
            ApiEndpoint create = catalog.endpoints().stream().filter(e -> e.id().equals(s.api())).findFirst()
                    .orElse(null);
            if (create == null) {
                continue;
            }
            Flow flow = flow(catalog, plan, s, create);
            if (flow != null) {
                flows.add(flow);
            }
        }
        return new LifecyclePlan(flows);
    }

    private static @Nullable Flow flow(ApiCatalog catalog, DataPlan plan, SeedPlan.Step s, ApiEndpoint create) {
        // the key later steps address the row by: the delete endpoint's pool, else the primary key
        String keyPool = s.deleteApi() != null ? s.deletePool() : s.pool();
        String keyProperty = keyPool.equals(s.pool()) ? s.idField() : s.captures().getOrDefault(keyPool, s.idField());
        List<ApiEndpoint> items = new ArrayList<>();
        for (ApiEndpoint e : catalog.endpoints()) {
            if (addresses(e, plan, keyPool) && e.path().startsWith(collection(create))) {
                items.add(e);
            }
        }
        ApiEndpoint get = first(items, HttpMethod.GET, true);
        ApiEndpoint update = firstWithBody(items, HttpMethod.PUT, HttpMethod.PATCH);
        ApiEndpoint delete = s.deleteApi() == null ? null
                : items.stream().filter(e -> e.id().equals(s.deleteApi())).findFirst().orElse(null);
        List<ApiEndpoint> actions = new ArrayList<>();
        for (ApiEndpoint e : items) {
            if ((e.method() == HttpMethod.POST || e.method() == HttpMethod.PUT || e.method() == HttpMethod.PATCH)
                    && e.path().matches(Pattern.quote(collection(create)) + "/\\{[^/}]+}/[^/{}]+")) {
                actions.add(e);
            }
        }
        if (get == null && update == null && delete == null && actions.isEmpty()) {
            return null;
        }
        ArrayNode steps = Documents.json().createArrayNode();
        step(steps, create, null, null, null, false);
        int created = 0; // index of the create step
        Object ref = ref(created, keyProperty);
        if (get != null) {
            step(steps, get, ref, null, null, false);
        }
        if (update != null) {
            step(steps, update, ref, null, null, false);
            String[] status = statusField(catalog, update);
            if (status != null) {
                List<String> values = enumValues(catalog, update, status[0]);
                for (int i = 1; i < Math.min(values.size(), MAX_TRANSITIONS + 1); i++) {
                    step(steps, update, ref, status[0], values.get(i), false);
                    if (get != null) {
                        step(steps, get, ref, null, null, false);
                    }
                }
            }
        }
        for (ApiEndpoint action : actions) {
            step(steps, action, ref, null, null, false);
            if (get != null) {
                step(steps, get, ref, null, null, false);
            }
        }
        if (delete != null) {
            step(steps, delete, ref, null, null, true);
        }
        if (steps.size() < 2) {
            return null;
        }
        return new Flow(s.table(), s.table(), steps);
    }

    /** The collection path of a create endpoint ({@code /orders}); item routes live below it. */
    private static String collection(ApiEndpoint create) {
        return create.path().replaceAll("/+$", "");
    }

    private static boolean addresses(ApiEndpoint e, DataPlan plan, String pool) {
        List<com.springaimcpservercommon.loadtest.model.ApiParam> path = e.params(ParamLocation.PATH);
        if (path.size() != 1) {
            return false;
        }
        FieldPlan f = plan.field(FieldKeys.param(e.id(), ParamLocation.PATH, path.getFirst().name()));
        return f != null && f.pool() != null && f.pool().key().equals(pool);
    }

    private static @Nullable ApiEndpoint first(List<ApiEndpoint> items, HttpMethod method, boolean itemRoute) {
        return items.stream().filter(e -> e.method() == method
                && e.path().matches(".*\\{[^/}]+}$") == itemRoute).findFirst().orElse(null);
    }

    private static @Nullable ApiEndpoint firstWithBody(List<ApiEndpoint> items, HttpMethod... methods) {
        for (HttpMethod m : methods) {
            for (ApiEndpoint e : items) {
                if (e.method() == m && e.body() != null && e.path().matches(".*\\{[^/}]+}$")) {
                    return e;
                }
            }
        }
        return null;
    }

    private static ObjectNode ref(int step, String property) {
        ObjectNode n = Documents.json().createObjectNode();
        n.put("$from", step);
        n.put("at", property);
        n.put("deep", true); // the id may sit in a wrapper: {"data": {"id": 7}}
        ArrayNode alt = n.putArray("alt");
        alt.addObject().put("$from", step).put("at", "$location"); // a create answering 201 + Location, no body
        return n;
    }

    private static void step(ArrayNode steps, ApiEndpoint e, @Nullable Object idRef, @Nullable String setPath,
                             @Nullable String setValue, boolean ownRow) {
        ObjectNode step = steps.addObject();
        step.put("api", e.id());
        ObjectNode path = step.putObject("path");
        if (idRef instanceof ObjectNode ref && !e.params(ParamLocation.PATH).isEmpty()) {
            path.set(e.params(ParamLocation.PATH).getFirst().name(), ref.deepCopy());
        }
        step.putObject("query");
        step.putObject("headers");
        step.putArray("fill");
        if (e.body() != null && e.method() != HttpMethod.GET && e.method() != HttpMethod.DELETE) {
            step.put("complete", true); // the row is written whole, every optional field included (like seeding)
        }
        if (setPath != null) {
            step.putObject("set").put(setPath, setValue);
        }
        step.put("pauseMs", steps.size() == 1 ? 0 : 100);
        if (ownRow) {
            step.put("ownRow", true);
        }
        if (steps.size() == 1) {
            step.put("stopOnFailure", true); // no row was created: the rest of the flow has nothing to work on
        }
    }

    // ── status transitions ─────────────────────────────────────────────────────────────────────────────

    /** {@code [property]} of an enum body property named like a status, if the update body has one. */
    private static String @Nullable [] statusField(ApiCatalog catalog, ApiEndpoint update) {
        for (Map.Entry<String, Property> p : properties(catalog, update.body()).entrySet()) {
            if (STATUS_NAME.matcher(p.getKey().toLowerCase(Locale.ROOT)).find()
                    && p.getValue().schema() instanceof ScalarSchema sc && sc.enumValues().size() >= 2) {
                return new String[] {p.getKey()};
            }
        }
        return null;
    }

    private static List<String> enumValues(ApiCatalog catalog, ApiEndpoint update, String property) {
        Property p = properties(catalog, update.body()).get(property);
        return p != null && p.schema() instanceof ScalarSchema sc ? sc.enumValues() : List.of();
    }

    private static Map<String, Property> properties(ApiCatalog catalog, @Nullable Schema body) {
        Schema s = body instanceof RefSchema(String name) ? catalog.schemas().get(name) : body;
        return s instanceof ObjectSchema o ? o.properties() : Map.of();
    }

    /**
     * Renders {@code data/lifecycle.json}.
     *
     * @return JSON array of flows ({@code name, resource, steps})
     */
    public ArrayNode toJson() {
        ArrayNode arr = Documents.json().createArrayNode();
        for (Flow f : flows) {
            ObjectNode n = arr.addObject();
            n.put("name", f.name());
            n.put("resource", f.resource());
            n.set("steps", f.steps());
        }
        return arr;
    }
}
