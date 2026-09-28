package com.springaimcpservercommon.ai.tool;

/**
 * Post-processing policy for a tool's result (LLD-07 §2).
 *
 * @param maxChars       maximum characters for the serialized result sent to the model;
 *                       0 means use the global default ({@code dynamic.ai.agent.tools.result-max-chars})
 * @param maskSensitive  whether to mask sensitive fields (always {@code true} by default)
 */
public record ResultPolicy(int maxChars, boolean maskSensitive) {

    /** Default policy: global char limit, sensitive fields masked. */
    public static final ResultPolicy DEFAULT = new ResultPolicy(0, true);

    /** Validates fields. */
    public ResultPolicy {
        if (maxChars < 0) throw new IllegalArgumentException("maxChars must be >= 0");
    }
}
