package com.springaimcpservercommon.ruleengine.model;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * "When {@code owner} produces {@code on}, communicate through {@code type}."
 *
 * @param id                  binding id
 * @param ownerType           rule or group
 * @param ownerId             rule or group id
 * @param on                  outcome that fires it
 * @param type                channel
 * @param sequence            order among the owner's bindings
 * @param emailTemplateId     EMAIL: template (key into the tenant data)
 * @param apiEndpointId       API: endpoint (key into the tenant data)
 * @param pushTitleMessage    PUSH: title bundle
 * @param pushBodyMessage     PUSH: body bundle
 * @param recipientExpression CEL expression yielding the recipient from the facts, or {@code null}
 */
public record ChannelBinding(UUID id, OwnerType ownerType, UUID ownerId, ChannelTrigger on, ChannelType type,
                             int sequence, @Nullable UUID emailTemplateId, @Nullable UUID apiEndpointId,
                             @Nullable UUID pushTitleMessage, @Nullable UUID pushBodyMessage,
                             @Nullable String recipientExpression) {
}
