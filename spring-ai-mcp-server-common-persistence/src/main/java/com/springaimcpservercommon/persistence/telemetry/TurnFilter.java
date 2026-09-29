package com.springaimcpservercommon.persistence.telemetry;

import com.springaimcpservercommon.core.invocation.Channel;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Optional narrowing of a workspace's turn list; a {@code null} component does not filter.
 *
 * @param principalId     only turns of this caller
 * @param agentResourceId only turns of this agent
 * @param outcome         only turns with this outcome
 * @param channel         only turns that came in through this channel
 */
public record TurnFilter(@Nullable UUID principalId, @Nullable UUID agentResourceId, @Nullable TurnOutcome outcome,
                         @Nullable Channel channel) {

    /** No filtering. */
    public static final TurnFilter NONE = new TurnFilter(null, null, null, null);
}
