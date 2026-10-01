package com.springaimcpservercommon.core.guard;

import java.util.List;
import java.util.Objects;

/** Outcome of validating a prompt: allowed, or rejected with a caller-safe reason. */
public sealed interface PromptVerdict permits PromptVerdict.Allowed, PromptVerdict.Rejected {

    /** The single "allowed" verdict. */
    Allowed ALLOW = new Allowed();

    /**
     * Allowed verdict.
     *
     * @return {@link #ALLOW}
     */
    static PromptVerdict allow() {
        return ALLOW;
    }

    /**
     * Rejected verdict.
     *
     * @param code     stable machine code in {@code snake_case}, e.g. {@code input_malicious}, {@code off_topic}
     * @param message  explanation that is safe to show the caller (never echoes the prompt)
     * @param findings rule or category names for logs and audit (never prompt content)
     * @return the verdict
     */
    static PromptVerdict reject(String code, String message, List<String> findings) {
        return new Rejected(code, message, findings);
    }

    /**
     * Whether the prompt may proceed.
     *
     * @return {@code true} for {@link Allowed}
     */
    default boolean allowed() {
        return this instanceof Allowed;
    }

    /** The prompt may proceed. */
    record Allowed() implements PromptVerdict {
    }

    /**
     * The prompt must not reach the model.
     *
     * @param code     stable machine code in {@code snake_case}
     * @param message  caller-safe explanation
     * @param findings rule or category names (no prompt content)
     */
    record Rejected(String code, String message, List<String> findings) implements PromptVerdict {

        /** Validates components. */
        public Rejected {
            Objects.requireNonNull(code, "code");
            if (!code.matches("[a-z][a-z0-9_]{1,63}")) {
                throw new IllegalArgumentException("code must be snake_case: " + code);
            }
            Objects.requireNonNull(message, "message");
            findings = List.copyOf(findings);
        }
    }
}
