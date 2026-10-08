package com.springaimcpservercommon.celfaker.values;

import com.springaimcpservercommon.ruleengine.model.DataType;

import java.util.ArrayList;
import java.util.List;

/**
 * The values a single parameter can be run with.
 *
 * <p>Values are plain JSON-compatible Java values; TIMESTAMP and DURATION are ISO-8601 strings, as the rule engine's
 * fact binding accepts them.
 *
 * @param name     CEL name {@code object.attribute}
 * @param type     CEL data type
 * @param kind     semantic kind detected from the sample (EMAIL, UUID, URL, PHONE, NAME, GENERIC, ...)
 * @param sample   the value seen in the payload
 * @param valid    plausible values an API accepts
 * @param boundary edge values (empty, zero, limits) that are valid CEL inputs
 * @param invalid  values of the wrong type or shape, for validation (negative) scenarios
 */
public record AttributeValues(String name, DataType type, String kind, Object sample, List<Object> valid,
                              List<Object> boundary, List<Object> invalid) {

    /** Defensive copies. */
    public AttributeValues {
        valid = new ArrayList<>(valid);
        boundary = new ArrayList<>(boundary);
        invalid = new ArrayList<>(invalid);
    }

    /**
     * The inputs to evaluate CEL with: valid values followed by boundary values, without duplicates.
     *
     * @return CEL input candidates
     */
    public List<Object> celInputs() {
        List<Object> all = new ArrayList<>(valid);
        for (Object b : boundary) {
            if (!all.contains(b)) {
                all.add(b);
            }
        }
        return all;
    }
}
