package com.springaimcpservercommon.loadtest.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A JSON object.
 *
 * @param properties properties in declaration order
 * @param freeForm   {@code true} for maps and untyped objects (no known properties)
 */
public record ObjectSchema(Map<String, Property> properties, boolean freeForm) implements Schema {

    /** Compact constructor: ordered, unmodifiable copy. */
    public ObjectSchema {
        properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    /**
     * A free-form object ({@code Map}, {@code JsonNode}, {@code Object}).
     *
     * @return the schema
     */
    public static ObjectSchema freeFormObject() {
        return new ObjectSchema(Map.of(), true);
    }
}
