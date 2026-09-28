package com.springaimcpservercommon.query.ast;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * A declared input parameter for a {@link QueryDefinition} (LLD-05 §2).
 *
 * <p>Query definitions declare parameters up front; callers supply their values at execution time.
 * The type information comes from the JSON Schema fragment so that the framework can coerce and
 * validate caller input before binding it into the JPA query.
 *
 * @param name         parameter name; must be unique within a query definition
 * @param jsonSchema   JSON Schema fragment (inline object, e.g. {@code {"type":"string"}}) describing the expected type
 * @param required     whether the caller must supply this parameter
 * @param defaultValue default scalar value applied when the parameter is not required and not supplied;
 *                     {@code null} means no default (the parameter is omitted from bindings)
 */
public record QueryParam(String name, String jsonSchema, boolean required, @Nullable Object defaultValue) {

    /** Validates the required fields. */
    public QueryParam {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("parameter name must not be blank");
        }
        Objects.requireNonNull(jsonSchema, "jsonSchema");
    }
}
