package com.springaimcpservercommon.core.policy;

import com.springaimcpservercommon.annotations.Classification;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One override entry of a policy document (LLD-03 §4.2). Every field is optional ({@code null} = not set).
 *
 * @param enabled             {@code false} disables the element; {@code true} has no effect (enabled is an AND)
 * @param reason              why; mandatory when {@code enabled == false}
 * @param descriptionOverride replaces the description / intent / meaning (latest layer wins)
 * @param keywords            replaces keywords (latest non-empty layer wins)
 * @param sensitive           attribute sensitivity; may only be raised unless an OVERLAY declassifies
 * @param classification      classification; may only be raised unless an OVERLAY declassifies
 * @param maxLimit            row cap; the effective value is the minimum across layers
 * @param mandatoryFilters    entity mandatory filters; the effective value is the union
 * @param readOnly            {@code true} forbids writes (a write action becomes disabled); {@code false} is rejected
 * @param declassify          explicit permission to lower {@code classification}/{@code sensitive}; OVERLAY only
 */
public record PolicyOverride(@Nullable Boolean enabled, @Nullable String reason,
                             @Nullable String descriptionOverride, @Nullable List<String> keywords,
                             @Nullable Boolean sensitive, @Nullable Classification classification,
                             @Nullable Integer maxLimit, @Nullable List<String> mandatoryFilters,
                             @Nullable Boolean readOnly, boolean declassify) {

    /** Validates invariants that hold for every layer and copies collections. */
    public PolicyOverride {
        if (Boolean.FALSE.equals(enabled) && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("reason is mandatory when enabled=false");
        }
        if (classification == Classification.INHERIT) {
            throw new IllegalArgumentException("classification INHERIT is not allowed in a policy");
        }
        if (maxLimit != null && maxLimit < 1) {
            throw new IllegalArgumentException("maxLimit must be >= 1");
        }
        if (declassify && classification == null && !Boolean.FALSE.equals(sensitive)) {
            throw new IllegalArgumentException("declassify requires classification or sensitive=false");
        }
        keywords = keywords == null ? null : List.copyOf(keywords);
        mandatoryFilters = mandatoryFilters == null ? null : List.copyOf(mandatoryFilters);
    }

    /**
     * An override that only disables the element.
     *
     * @param reason why (mandatory)
     * @return the override
     */
    public static PolicyOverride disable(String reason) {
        return new PolicyOverride(Boolean.FALSE, reason, null, null, null, null, null, null, null, false);
    }

    /**
     * Canonical value-tree form (set fields only), for fingerprints.
     *
     * @return map of set fields
     */
    Map<String, Object> canonical() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (enabled != null) {
            m.put("enabled", enabled);
        }
        if (reason != null) {
            m.put("reason", reason);
        }
        if (descriptionOverride != null) {
            m.put("descriptionOverride", descriptionOverride);
        }
        if (keywords != null) {
            m.put("keywords", keywords);
        }
        if (sensitive != null) {
            m.put("sensitive", sensitive);
        }
        if (classification != null) {
            m.put("classification", classification);
        }
        if (maxLimit != null) {
            m.put("maxLimit", maxLimit);
        }
        if (mandatoryFilters != null) {
            m.put("mandatoryFilters", mandatoryFilters);
        }
        if (readOnly != null) {
            m.put("readOnly", readOnly);
        }
        if (declassify) {
            m.put("declassify", true);
        }
        return m;
    }
}
