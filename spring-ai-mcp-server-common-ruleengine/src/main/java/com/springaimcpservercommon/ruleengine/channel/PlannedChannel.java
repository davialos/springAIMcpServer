package com.springaimcpservercommon.ruleengine.channel;

import com.springaimcpservercommon.ruleengine.model.ApiEndpoint;
import com.springaimcpservercommon.ruleengine.model.ChannelBinding;
import com.springaimcpservercommon.ruleengine.model.EmailTemplate;
import org.jspecify.annotations.Nullable;

/**
 * A communication the evaluation asks for, fully resolved but not yet sent.
 *
 * @param binding        the configured binding
 * @param ruleCode       the rule that fired it, or {@code null} for a group binding
 * @param recipient      resolved recipient, or {@code null} (EMAIL/PUSH without one are skipped); personal data
 * @param language       language for the end user
 * @param emailTemplate  EMAIL: the template
 * @param apiEndpoint    API: the endpoint
 * @param pushTitle      PUSH: localized title
 * @param pushBody       PUSH: localized body
 */
public record PlannedChannel(ChannelBinding binding, @Nullable String ruleCode, @Nullable String recipient,
                             String language, @Nullable EmailTemplate emailTemplate,
                             @Nullable ApiEndpoint apiEndpoint, @Nullable String pushTitle,
                             @Nullable String pushBody) {
}
