package com.springaimcpservercommon.loadtest.model;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything discovered about a project: its REST operations, the named request schemas they use and the JPA
 * entities behind them.
 *
 * @param project   project name (directory or OpenAPI title)
 * @param basePath  servlet context path / server URL path prefix, if any (e.g. {@code /shop})
 * @param endpoints operations, in discovery order
 * @param schemas   named schemas referenced by {@link RefSchema}
 * @param entities  JPA entities found in the sources
 */
public record ApiCatalog(String project, @Nullable String basePath, List<ApiEndpoint> endpoints,
                         Map<String, ObjectSchema> schemas, List<EntityTable> entities) {

    /** Compact constructor: defensive copies. */
    public ApiCatalog {
        endpoints = List.copyOf(endpoints);
        schemas = Collections.unmodifiableMap(new LinkedHashMap<>(schemas));
        entities = List.copyOf(entities);
    }

    /**
     * Resolves a schema through at most one level of reference.
     *
     * @param schema schema, possibly a {@link RefSchema}
     * @return the referenced object schema, or the schema itself; a free-form object for a dangling reference
     */
    public Schema resolve(Schema schema) {
        if (schema instanceof RefSchema(String name)) {
            ObjectSchema target = schemas.get(name);
            return target != null ? target : ObjectSchema.freeFormObject();
        }
        return schema;
    }
}
