package com.springaimcpservercommon.ruleengine.eval;

import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * What an integrating application sends. Either a {@code trigger} (the form action the user took; the engine finds the
 * rule groups bound to it) or explicit {@code groups} (rule group codes).
 *
 * @param module   the module the rules belong to ({@code LENDING})
 * @param trigger  the trigger point: a form, the action and optionally the field
 * @param groups   rule group codes to evaluate instead of a trigger
 * @param context  the data the rules look at: object code → attribute → value ({@code {"customer": {"age": 20}}})
 * @param language the language of the messages ({@code en}, {@code hi}, {@code th}); default: the tenant's
 * @param dryRun   evaluate without writing the audit log or calling channels
 * @param detailed include the per-group and per-rule raw results and the channel dispatches
 */
public record EvaluateRequest(@NotBlank String module, @Nullable Trigger trigger, @Nullable List<String> groups,
                              @Nullable Map<String, Map<String, Object>> context, @Nullable String language,
                              @Nullable Boolean dryRun, @Nullable Boolean detailed) {

    /**
     * A trigger point.
     *
     * @param type   {@code FORM}
     * @param form   the form code ({@code LOAN_APPLICATION})
     * @param action what the user did ({@code SUBMIT}, {@code APPROVE}, {@code ADD}, {@code BUY} …)
     * @param field  the field the action concerns, or {@code null} for the whole form
     */
    public record Trigger(@Nullable String type, @NotBlank String form, @NotBlank String action,
                          @Nullable String field) { }

    /**
     * @return the context, never {@code null}
     */
    public Map<String, Map<String, Object>> contextOrEmpty() {
        return context == null ? Map.of() : context;
    }
}
