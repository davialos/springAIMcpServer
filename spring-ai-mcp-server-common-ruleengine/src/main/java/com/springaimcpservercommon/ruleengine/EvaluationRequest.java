package com.springaimcpservercommon.ruleengine;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A request to evaluate one rule group.
 *
 * @param tenantId       tenant that owns the rules
 * @param organizationId caller's organization, or {@code null}; an organization's own group shadows the tenant-wide one
 * @param moduleCode     module the group belongs to
 * @param groupCode      group to evaluate
 * @param facts          values for library parameters, flat ({@code "customer.age": 34}) or nested
 * @param languages      end user's preferred languages (e.g. from Accept-Language), most preferred first
 */
public record EvaluationRequest(UUID tenantId, @Nullable UUID organizationId, String moduleCode, String groupCode,
                                Map<String, Object> facts, List<String> languages) {

    /** Defensive copy of the language list. */
    public EvaluationRequest {
        languages = List.copyOf(languages);
    }
}
