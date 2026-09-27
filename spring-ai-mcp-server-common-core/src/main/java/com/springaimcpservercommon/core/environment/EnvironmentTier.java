package com.springaimcpservercommon.core.environment;

import java.util.Locale;
import java.util.Optional;

/**
 * Deployment tier (LLD-12 §2). {@link #UNKNOWN} is handled exactly like {@link #PROD} (fail closed).
 */
public enum EnvironmentTier {
    /** Developer machine. */
    DEV,
    /** Automated / shared test environment. */
    TEST,
    /** Pre-production. */
    STAGE,
    /** Production. */
    PROD,
    /** Nothing declared; treated as production. */
    UNKNOWN;

    /**
     * Whether production rules apply (PROD or UNKNOWN).
     *
     * @return {@code true} for PROD and UNKNOWN
     */
    public boolean productionRules() {
        return this == PROD || this == UNKNOWN;
    }

    /**
     * Parses a configured tier name case-insensitively ({@code dev}, {@code TEST}, …).
     *
     * @param text configured value
     * @return the tier, or empty if the value is not a tier name
     */
    public static Optional<EnvironmentTier> parse(String text) {
        try {
            return Optional.of(valueOf(text.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
