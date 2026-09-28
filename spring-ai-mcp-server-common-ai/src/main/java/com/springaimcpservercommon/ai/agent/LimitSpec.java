package com.springaimcpservercommon.ai.agent;

import java.time.Duration;
import java.util.Objects;

/**
 * Per-turn and per-conversation limits for an agent (LLD-06 §9).
 *
 * @param maxToolCallsPerTurn     maximum tool calls in a single turn (default 10)
 * @param maxTokensPerTurn        maximum tokens (input + output) per turn; 0 = unlimited
 * @param turnTimeout             maximum wall-clock time for a complete turn including all tool calls
 * @param maxTurnsPerConversation maximum turns before the conversation is terminated; 0 = unlimited
 */
public record LimitSpec(
        int maxToolCallsPerTurn,
        int maxTokensPerTurn,
        Duration turnTimeout,
        int maxTurnsPerConversation) {

    /** Default limits matching LLD-06 §9. */
    public static final LimitSpec DEFAULT = new LimitSpec(10, 0, Duration.ofSeconds(60), 0);

    /** Validates fields. */
    public LimitSpec {
        if (maxToolCallsPerTurn < 1) throw new IllegalArgumentException("maxToolCallsPerTurn must be >= 1");
        if (maxTokensPerTurn < 0) throw new IllegalArgumentException("maxTokensPerTurn must be >= 0");
        Objects.requireNonNull(turnTimeout, "turnTimeout");
        if (turnTimeout.isNegative() || turnTimeout.isZero()) {
            throw new IllegalArgumentException("turnTimeout must be positive");
        }
        if (maxTurnsPerConversation < 0) throw new IllegalArgumentException("maxTurnsPerConversation must be >= 0");
    }
}
