package com.springaimcpservercommon.core.catalog;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * One parameter of an exposed action. Its JSON schema lives inside the operation's input schema (under
 * {@code properties.<name>}) so that shared {@code $defs} stay resolvable.
 *
 * @param name        effective name ({@code @AiParam.name} or the reflected name)
 * @param index       zero-based position in the Java signature
 * @param javaType    generic Java type name (e.g. {@code java.util.List<java.lang.Long>})
 * @param description {@code @AiParam.description}, if annotated
 * @param required    whether the model must supply it
 * @param sensitive   value redacted in traces, audit and echoed filters
 * @param kind        what the parameter is used for
 * @param details     {@code @AiParam.details}: plain-English notes on how to tell a right value from a wrong one
 * @param examples    {@code @AiParam.examples}: made-up example values (empty for a sensitive parameter)
 */
public record ParamDescriptor(String name, int index, String javaType, @Nullable String description,
                              boolean required, boolean sensitive, Kind kind, @Nullable String details,
                              List<String> examples) {

    /** Creates a parameter without details or examples. */
    public ParamDescriptor(String name, int index, String javaType, @Nullable String description,
                           boolean required, boolean sensitive, Kind kind) {
        this(name, index, javaType, description, required, sensitive, kind, null, List.of());
    }

    /** Role of a parameter. */
    public enum Kind {
        /** An ordinary value supplied by the model. */
        VALUE,
        /** A Spring Data {@code Pageable}; exposed to the model as {@code {page,size}} and converted by the tool bridge. */
        PAGEABLE,
        /** A Spring Data {@code Limit}; exposed as an integer and converted by the tool bridge. */
        SPRING_DATA_LIMIT,
        /** An integral {@code @AiParam}-annotated row limit (e.g. {@code limit}); capped by the effective max limit. */
        LIMIT
    }

    /** Validates components. */
    public ParamDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(javaType, "javaType");
        Objects.requireNonNull(kind, "kind");
        examples = List.copyOf(examples);
        if (index < 0) {
            throw new IllegalArgumentException("index must be >= 0");
        }
    }
}
