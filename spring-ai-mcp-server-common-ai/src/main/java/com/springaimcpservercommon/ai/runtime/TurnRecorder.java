package com.springaimcpservercommon.ai.runtime;

import com.springaimcpservercommon.ai.agent.AgentDefinition;
import com.springaimcpservercommon.core.invocation.Channel;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Port: receives one record per finished agent turn (F-72), for the trace viewer, usage dashboard and audit
 * correlation. The default implementation in {@code autoconfigure} writes to the {@code dynamic_ai} store; the
 * default when no store exists is a no-op.
 *
 * <p>Implementations must be cheap and must never fail or slow the turn: the invoker calls {@link #record} on
 * the request thread (or a Reactor thread for streams) and ignores anything it throws. A record contains no
 * prompt, answer or row data, only identifiers, timings, token counts and outcome codes.
 */
@FunctionalInterface
public interface TurnRecorder {

    /** Recorder that discards everything. */
    TurnRecorder NOOP = turn -> { };

    /**
     * Records a finished turn. Called exactly once per turn, including refused, failed and cancelled ones.
     *
     * @param turn what happened
     */
    void record(TurnRecord turn);

    /** How a turn ended (mirrors the persisted turn outcome). */
    enum Outcome {
        /** The model produced an answer. */
        SUCCESS,
        /** An error ended the turn. */
        FAILED,
        /** The client went away or cancelled the stream. */
        CANCELLED,
        /** The turn was refused before any model work (budget). */
        REJECTED
    }

    /** Why a turn ended (mirrors the persisted finish reason). */
    enum Finish {
        /** The model finished normally. */
        STOP,
        /** The turn was cut by a token limit. */
        LENGTH,
        /** The turn hit the tool-call limit. */
        TOOL_LIMIT,
        /** A budget refused the turn. */
        BUDGET,
        /** The client cancelled. */
        CANCELLED,
        /** An error ended the turn. */
        ERROR
    }

    /**
     * A finished agent turn.
     *
     * @param turnId             the id the client saw (SSE ids, replay URL, response)
     * @param startedAt          when the turn began
     * @param endedAt            when the turn ended
     * @param conversationId     conversation, if any
     * @param agent              the agent (workspace, id, configured model)
     * @param principal          the caller
     * @param channel            how the turn was requested
     * @param traceId            trace correlation id when one was active, else {@code null}
     * @param clientRequestId    the client's request id, if it sent one
     * @param outcome            outcome
     * @param finish             finish reason
     * @param errorCode          stable error code; mandatory unless the outcome is SUCCESS
     * @param timeToFirstTokenMs milliseconds to the first streamed text, or {@code null}
     * @param streaming          whether the turn was streamed
     * @param inputTokens        input tokens reported by the provider (0 when unknown)
     * @param outputTokens       output tokens reported by the provider (0 when unknown)
     */
    record TurnRecord(UUID turnId, Instant startedAt, Instant endedAt, @Nullable UUID conversationId,
                      AgentDefinition agent, DaiPrincipal principal, Channel channel, @Nullable String traceId,
                      @Nullable String clientRequestId, Outcome outcome, Finish finish, @Nullable String errorCode,
                      @Nullable Integer timeToFirstTokenMs, boolean streaming, long inputTokens,
                      long outputTokens) {

        /** Validates the components. */
        public TurnRecord {
            Objects.requireNonNull(turnId, "turnId");
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(endedAt, "endedAt");
            Objects.requireNonNull(agent, "agent");
            Objects.requireNonNull(principal, "principal");
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(finish, "finish");
            if (outcome != Outcome.SUCCESS && errorCode == null) {
                throw new IllegalArgumentException("a turn that did not succeed needs an errorCode");
            }
            if (endedAt.isBefore(startedAt)) {
                endedAt = startedAt;
            }
        }
    }
}
