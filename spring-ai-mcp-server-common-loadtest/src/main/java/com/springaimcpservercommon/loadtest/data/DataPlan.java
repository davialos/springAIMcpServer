package com.springaimcpservercommon.loadtest.data;

import com.springaimcpservercommon.loadtest.model.ApiCatalog;
import com.springaimcpservercommon.loadtest.model.ApiEndpoint;
import com.springaimcpservercommon.loadtest.model.ApiParam;
import com.springaimcpservercommon.loadtest.model.ArraySchema;
import com.springaimcpservercommon.loadtest.model.Names;
import com.springaimcpservercommon.loadtest.model.ObjectSchema;
import com.springaimcpservercommon.loadtest.model.ParamLocation;
import com.springaimcpservercommon.loadtest.model.Property;
import com.springaimcpservercommon.loadtest.model.RefSchema;
import com.springaimcpservercommon.loadtest.model.ScalarSchema;
import com.springaimcpservercommon.loadtest.model.Schema;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The data plan of a suite: every scalar request field of every API, its kind and its real-data pool.
 * Built once from the catalog; the JS generator reads it while emitting providers, the samplers read
 * {@link #pools()} to know what to fetch.
 *
 * @param fields field key → plan, in API order
 */
public record DataPlan(Map<String, FieldPlan> fields) {

    /** Compact constructor: ordered, unmodifiable copy. */
    public DataPlan {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    /**
     * The plan of a field.
     *
     * @param key field key
     * @return plan, or {@code null} when the key is unknown
     */
    public @Nullable FieldPlan field(String key) {
        return fields.get(key);
    }

    /**
     * Distinct real-data pools referenced by any field.
     *
     * @return pools
     */
    public Collection<PoolRef> pools() {
        Set<PoolRef> out = new LinkedHashSet<>();
        for (FieldPlan f : fields.values()) {
            if (f.pool() != null) {
                out.add(f.pool());
            }
        }
        return out;
    }

    /**
     * Builds the plan of a catalog.
     *
     * @param catalog discovered APIs
     * @param binder  real-data binder
     * @return the plan
     */
    public static DataPlan build(ApiCatalog catalog, RealDataBinder binder) {
        Builder b = new Builder(catalog, binder);
        for (ApiEndpoint e : catalog.endpoints()) {
            String resource = e.resource() != null ? e.resource() : ApiHarvester.lastSegment(e.path());
            for (ApiParam p : e.params()) {
                String key = FieldKeys.param(e.id(), p.in(), p.name());
                String hint = p.in() == ParamLocation.PATH ? segmentBefore(e.path(), p.name(), resource) : resource;
                b.walk(p.schema(), key, p.name(), e.id(), p.in(), Names.isSensitive(p.name()), hint);
            }
            if (e.body() != null) {
                b.walk(e.body(), FieldKeys.body(e.id()), "body", e.id(), null, false, resource);
            }
        }
        return new DataPlan(b.fields);
    }

    /** {@code /orders/{id}/lines/{lineId}}: the collection right before {@code {id}} is {@code orders}. */
    static @Nullable String segmentBefore(String path, String var, @Nullable String fallback) {
        String[] parts = path.split("/");
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].equals("{" + var + "}") && !parts[i - 1].isBlank() && !parts[i - 1].startsWith("{")) {
                return parts[i - 1];
            }
        }
        return fallback;
    }

    /**
     * Resource a named DTO is about: {@code CreateOrderRequest} → {@code Order}.
     *
     * @param schemaName schema name
     * @return resource hint
     */
    static String schemaResource(String schemaName) {
        return schemaName.replaceAll("^(Create|Update|Patch|New|Add|Edit|Upsert|Save)", "")
                .replaceAll("(Request|Dto|DTO|Command|Payload|Form|Input|Body|Data|Model|Resource|Params)+$", "");
    }

    private static final class Builder {
        private final ApiCatalog catalog;
        private final RealDataBinder binder;
        private final Map<String, FieldPlan> fields = new LinkedHashMap<>();
        private final Set<String> visitedSchemas = new HashSet<>();

        Builder(ApiCatalog catalog, RealDataBinder binder) {
            this.catalog = catalog;
            this.binder = binder;
        }

        void walk(Schema schema, String key, String name, String owner, @Nullable ParamLocation location,
                  boolean sensitive, @Nullable String resource) {
            switch (schema) {
                case ScalarSchema s -> {
                    FieldKind kind = FieldKindClassifier.classify(name, s);
                    PoolRef pool = binder.bind(new RealDataBinder.FieldContext(key, name, location, kind,
                            sensitive, resource, owner)).orElse(null);
                    fields.putIfAbsent(key, new FieldPlan(key, name, owner, kind, pool, sensitive));
                }
                case ArraySchema a -> walk(a.items(), key, name, owner, location, sensitive, resource);
                case ObjectSchema o -> {
                    for (Map.Entry<String, Property> p : o.properties().entrySet()) {
                        walk(p.getValue().schema(), FieldKeys.child(key, p.getKey()), p.getKey(), owner, null,
                                p.getValue().sensitive(), resource);
                    }
                }
                case RefSchema(String ref) -> {
                    ObjectSchema target = catalog.schemas().get(ref);
                    if (target != null && visitedSchemas.add(ref)) {
                        walk(target, ref, ref, ref, null, false, schemaResource(ref));
                    }
                }
            }
        }
    }
}
