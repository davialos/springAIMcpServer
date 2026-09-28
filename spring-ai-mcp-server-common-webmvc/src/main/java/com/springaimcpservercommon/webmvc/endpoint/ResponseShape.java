package com.springaimcpservercommon.webmvc.endpoint;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Post-execution response shaping: projection, field renaming, masking, and envelope (LLD-04 §4 step 9).
 *
 * @param includeFields   when non-empty, only these field names are included in each row (projection)
 * @param excludeFields   field names to always exclude (sensitive fields always excluded regardless)
 * @param fieldRenames    field renames: original name → output name
 * @param envelope        whether to wrap data in a standard envelope ({@code {data: [...], count: N}})
 * @param maskingEnabled  whether to apply classification-level field masking
 */
public record ResponseShape(
        List<String> includeFields,
        List<String> excludeFields,
        Map<String, String> fieldRenames,
        boolean envelope,
        boolean maskingEnabled) {

    /** Default shape: no projection, no renames, standard envelope, masking enabled. */
    public static final ResponseShape DEFAULT = new ResponseShape(
            List.of(), List.of(), Map.of(), true, true);

    /** Validates and copies. */
    public ResponseShape {
        Objects.requireNonNull(includeFields, "includeFields");
        Objects.requireNonNull(excludeFields, "excludeFields");
        Objects.requireNonNull(fieldRenames, "fieldRenames");
        includeFields = List.copyOf(includeFields);
        excludeFields = List.copyOf(excludeFields);
        fieldRenames = Map.copyOf(fieldRenames);
    }
}
