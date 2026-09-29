package com.springaimcpservercommon.ai.runtime;

import com.springaimcpservercommon.ai.agent.AgentDefinition;
import com.springaimcpservercommon.core.invocation.Channel;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import org.jspecify.annotations.NullMarked;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Port: receives the user message and the answer of every successful agent turn so the conversation can be shown
 * back to its owner and erased on request (F-44). The default is a no-op; {@code autoconfigure} provides a
 * store-backed recorder that is off unless {@code dynamic.ai.agent.conversations.enabled=true}, because
 * transcripts contain what users typed.
 *
 * <p>Only successful turns are reported: a refused, failed or cancelled turn has no answer. Implementations must
 * be cheap, must redact before storing and must never fail the turn; the invoker ignores anything they throw.
 */
@FunctionalInterface
public interface ConversationRecorder {

    /** Recorder that discards everything. */
    ConversationRecorder NOOP = exchange -> { };

    /**
     * Records one user message and its answer.
     *
     * @param exchange the message pair
     */
    void record(Exchange exchange);

    /**
     * A user message and the answer to it.
     *
     * @param conversationId  the conversation id the client uses
     * @param turnId          the turn that produced the answer
     * @param agent           the agent
     * @param principal       the owner of the conversation
     * @param channel         how the turn was requested
     * @param userMessage     what the user sent (unredacted; the recorder redacts)
     * @param assistantAnswer what the agent answered (unredacted; the recorder redacts)
     * @param at              when the turn started
     */
    record Exchange(UUID conversationId, UUID turnId, AgentDefinition agent, DaiPrincipal principal,
                    Channel channel, String userMessage, String assistantAnswer, Instant at) {

        /** Validates the components. */
        public Exchange {
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(turnId, "turnId");
            Objects.requireNonNull(agent, "agent");
            Objects.requireNonNull(principal, "principal");
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(userMessage, "userMessage");
            Objects.requireNonNull(assistantAnswer, "assistantAnswer");
            Objects.requireNonNull(at, "at");
        }
    }
}
