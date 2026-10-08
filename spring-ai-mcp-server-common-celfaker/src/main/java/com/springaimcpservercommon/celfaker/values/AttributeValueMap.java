package com.springaimcpservercommon.celfaker.values;

import com.springaimcpservercommon.celfaker.payload.JsonValues;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The attribute map file: for every {@code object.attribute} the valid, boundary and invalid values to run it with.
 * Generated, then edited by hand (add real ids, business values); loaded back by every later step.
 *
 * @param version    format version
 * @param seed       seed the values were generated with
 * @param attributes values per CEL name, in parameter order
 */
public record AttributeValueMap(int version, long seed, Map<String, AttributeValues> attributes) {

    /** Current format version. */
    public static final int VERSION = 1;

    /** Defensive copy. */
    public AttributeValueMap {
        attributes = new LinkedHashMap<>(attributes);
    }

    /**
     * Looks one parameter up.
     *
     * @param celName {@code object.attribute}
     * @return its values, if mapped
     */
    public Optional<AttributeValues> find(String celName) {
        return Optional.ofNullable(attributes.get(celName));
    }

    /**
     * Pretty-printed JSON.
     *
     * @return the file content
     */
    public String toJson() {
        return JsonValues.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(this);
    }

    /**
     * Reads a map file.
     *
     * @param json file content
     * @return the map
     */
    public static AttributeValueMap fromJson(String json) {
        return JsonValues.MAPPER.readValue(json, AttributeValueMap.class);
    }
}
