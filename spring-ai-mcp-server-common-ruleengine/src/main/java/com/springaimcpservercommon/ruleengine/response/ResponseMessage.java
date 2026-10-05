package com.springaimcpservercommon.ruleengine.response;

import com.springaimcpservercommon.ruleengine.model.Action;
import com.springaimcpservercommon.ruleengine.model.Outcome;
import org.jspecify.annotations.Nullable;

/**
 * One localized message for the end user.
 *
 * @param source   rule or group
 * @param ruleCode the rule, or {@code null} for a group message
 * @param outcome  the outcome it reports
 * @param action   the action tied to the outcome
 * @param language the language the text is in (may differ from the requested one after fallback)
 * @param text     the text
 */
public record ResponseMessage(MessageSource source, @Nullable String ruleCode, Outcome outcome, Action action,
                              String language, String text) {
}
