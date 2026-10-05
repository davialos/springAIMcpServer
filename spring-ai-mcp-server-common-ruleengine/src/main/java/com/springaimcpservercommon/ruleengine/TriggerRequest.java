package com.springaimcpservercommon.ruleengine;

import com.springaimcpservercommon.ruleengine.model.TriggerType;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An event in an integrating application: "the user pressed SUBMIT on form X" or "changed field Y of form X".
 *
 * @param tenantId       tenant
 * @param organizationId caller's organization, or {@code null}
 * @param application    integrating application code
 * @param type           FORM_ACTION or FORM_FIELD
 * @param formCode       form
 * @param actionCode     action (SUBMIT, APPROVE, ADD, BUY ...) or field event (ON_CHANGE)
 * @param fieldCode      field, required for FORM_FIELD
 * @param facts          values for library parameters
 * @param languages      end user's preferred languages
 */
public record TriggerRequest(UUID tenantId, @Nullable UUID organizationId, String application, TriggerType type,
                             String formCode, String actionCode, @Nullable String fieldCode,
                             Map<String, Object> facts, List<String> languages) {

    /** Defensive copy of the language list. */
    public TriggerRequest {
        languages = List.copyOf(languages);
    }
}
