package com.springaimcpservercommon.persistence.telemetry;

import com.springaimcpservercommon.core.invocation.Channel;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * A completed agent turn to record (one row, written when the turn ends).
 *
 * @param id                 turn id (UUIDv7, already used by model calls and tool invocations of the turn)
 * @param startedAt          start
 * @param endedAt            end
 * @param conversationId     conversation, if any
 * @param workspaceId        workspace of the agent
 * @param agentResourceId    agent resource, if any
 * @param agentRevisionId    agent revision that ran, if any
 * @param principalId        caller
 * @param channel            entry channel
 * @param traceId            trace id, if tracing is active
 * @param clientRequestId    client-supplied request id, if any
 * @param finishReason       why the turn ended
 * @param outcome            outcome
 * @param errorCode          error code; required unless the outcome is SUCCESS
 * @param timeToFirstTokenMs streaming latency to the first token, if measured
 */
public record NewAgentTurn(
        UUID id,
        Instant startedAt,
        Instant endedAt,
        @Nullable UUID conversationId,
        UUID workspaceId,
        @Nullable UUID agentResourceId,
        @Nullable UUID agentRevisionId,
        UUID principalId,
        Channel channel,
        @Nullable String traceId,
        @Nullable String clientRequestId,
        TurnFinishReason finishReason,
        TurnOutcome outcome,
        @Nullable String errorCode,
        @Nullable Integer timeToFirstTokenMs) {
}
