package com.springaimcpservercommon.persistence.telemetry;

import com.springaimcpservercommon.core.invocation.Channel;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.UUID;

/**
 * A conversation to open (or find) by its key hash.
 *
 * @param conversationKeyHash {@code sha256:} of (principal, agent, client session) — LLD-06 §7; a guessed
 *                            conversation id alone never grants access
 * @param workspaceId         workspace
 * @param agentResourceId     agent resource, if any
 * @param principalId         owner
 * @param channel             entry channel
 * @param title               optional title (already redacted)
 * @param retention           how long the conversation is kept after its last activity (positive, at most 10
 *                            years); the span slides forward with every appended message
 */
public record NewConversation(
        String conversationKeyHash,
        UUID workspaceId,
        @Nullable UUID agentResourceId,
        UUID principalId,
        Channel channel,
        @Nullable String title,
        Duration retention) {
}
