package com.springaimcpservercommon.loadtest.model;

/**
 * Reference to a named schema in {@link ApiCatalog#schemas()} (a DTO class or an OpenAPI component).
 *
 * @param name schema name
 */
public record RefSchema(String name) implements Schema {
}
