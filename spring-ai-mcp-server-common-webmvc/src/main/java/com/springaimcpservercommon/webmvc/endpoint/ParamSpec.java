package com.springaimcpservercommon.webmvc.endpoint;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Parameter declaration for a dynamic endpoint (LLD-04 §2).
 *
 * @param name       parameter name as it appears in the binding expression
 * @param in         where the parameter is bound in the request
 * @param jsonSchema JSON Schema string that values must conform to
 * @param required   whether the parameter is required (missing required → 400)
 * @param defaultValue default value applied when the parameter is absent and not required; {@code null} = no default
 */
public record ParamSpec(
        String name,
        ParamIn in,
        String jsonSchema,
        boolean required,
        @Nullable Object defaultValue) {

    /** Validates required fields. */
    public ParamSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(jsonSchema, "jsonSchema");
        if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
    }
}
