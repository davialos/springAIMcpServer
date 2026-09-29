package com.springaimcpservercommon.ai.agent;

import org.jspecify.annotations.Nullable;

import java.time.Duration;

/**
 * Conversation memory configuration for an agent (LLD-06 §7).
 *
 * <p>Strategy controls what is passed to the model as prior context on each turn:
 * <ul>
 *   <li>{@link Strategy#NONE} — stateless; no history sent to the model.</li>
 *   <li>{@link Strategy#WINDOW} — the last {@link #windowSize} full message pairs.</li>
 *   <li>{@link Strategy#SUMMARY} — a running LLM-generated summary compressed by {@link com.springaimcpservercommon.ai.advisor.SummaryMemoryAdvisor}.</li>
 * </ul>
 *
 * @param strategy   memory strategy
 * @param windowSize number of message pairs to retain (only for {@link Strategy#WINDOW}; default 10)
 * @param retention  how long to retain conversation history; {@code null} means indefinite
 */
public record MemorySpec(Strategy strategy, int windowSize, @Nullable Duration retention) {

    /** No history. */
    public static final MemorySpec NONE = new MemorySpec(Strategy.NONE, 0, null);
    /** Sliding window of 10 message pairs. */
    public static final MemorySpec WINDOW_10 = new MemorySpec(Strategy.WINDOW, 10, null);

    /** Memory strategy. */
    public enum Strategy {
        NONE,
        WINDOW,
        /** LLM-generated running summary via {@link com.springaimcpservercommon.ai.advisor.SummaryMemoryAdvisor}. */
        SUMMARY
    }

    /** Validates the combination. */
    public MemorySpec {
        if (strategy == Strategy.WINDOW && windowSize < 1) {
            throw new IllegalArgumentException("windowSize must be >= 1 for WINDOW strategy");
        }
        if (strategy != Strategy.WINDOW && windowSize != 0) {
            throw new IllegalArgumentException("windowSize is only relevant for WINDOW strategy");
        }
    }
}
