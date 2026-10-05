package com.springaimcpservercommon.ruleengine.model;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Binds a place in an integrating application to a rule group.
 *
 * @param id             trigger id
 * @param organizationId organization it applies to, or {@code null} = every organization of the tenant
 * @param application    integrating application code
 * @param type           trigger type
 * @param formCode       form
 * @param actionCode     action (SUBMIT, APPROVE ...) or field event (ON_CHANGE)
 * @param fieldCode      field for FORM_FIELD triggers
 * @param ruleGroupId    group to evaluate
 * @param sequence       order when several groups share a trigger
 */
public record TriggerPoint(UUID id, @Nullable UUID organizationId, String application, TriggerType type,
                           String formCode, String actionCode, @Nullable String fieldCode, UUID ruleGroupId,
                           int sequence) {
}
