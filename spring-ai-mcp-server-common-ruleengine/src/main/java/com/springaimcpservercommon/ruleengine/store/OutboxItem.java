package com.springaimcpservercommon.ruleengine.store;

import com.springaimcpservercommon.ruleengine.model.ChannelType;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * A claimed outbox row.
 *
 * @param id            dispatch id (sent to API receivers for de-duplication)
 * @param tenantId      tenant
 * @param channelType   channel
 * @param apiEndpointId API: endpoint id
 * @param payloadJson   what to send (contains the recipient for e-mail/push: personal data, never logged)
 * @param attempts      attempts including this one
 * @param maxAttempts   attempts allowed before the row is dead
 * @param createdAt     when it was queued
 */
public record OutboxItem(UUID id, UUID tenantId, ChannelType channelType, @Nullable UUID apiEndpointId,
                         String payloadJson, int attempts, int maxAttempts, Instant createdAt) {
}
