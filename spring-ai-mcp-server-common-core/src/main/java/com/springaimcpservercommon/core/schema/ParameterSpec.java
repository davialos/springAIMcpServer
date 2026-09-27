package com.springaimcpservercommon.core.schema;

import org.jspecify.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.Objects;

/**
 * One tool parameter to include in an input schema.
 *
 * @param name        effective parameter name
 * @param type        generic parameter type
 * @param description {@code @AiParam.description}, if any
 * @param required    whether it goes into {@code required}
 */
public record ParameterSpec(String name, Type type, @Nullable String description, boolean required) {

    /** Validates components. */
    public ParameterSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
    }
}
