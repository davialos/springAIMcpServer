package com.springaimcpservercommon.persistence.unit;

import java.io.Serial;

/**
 * Thrown at start-up when the store's {@code dai_environment} row belongs to another environment than the running
 * application (LLD-12 §3 "config-store identity" guard) — e.g. a stage application pointed at the production store.
 * The caller must disable the feature (data plane serves nothing, health DOWN) and log CRITICAL; it must never
 * "fix" the row.
 */
public final class StoreEnvironmentMismatchException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String expectedEnvironmentId;
    private final String expectedTier;
    private final String foundEnvironmentId;
    private final String foundTier;

    /**
     * Creates the exception.
     *
     * @param expectedEnvironmentId environment id of this application
     * @param expectedTier          tier of this application
     * @param foundEnvironmentId    environment id recorded in the store
     * @param foundTier             tier recorded in the store
     */
    public StoreEnvironmentMismatchException(String expectedEnvironmentId, String expectedTier,
                                             String foundEnvironmentId, String foundTier) {
        super("dynamic_ai store belongs to environment '" + foundEnvironmentId + "' (" + foundTier
                + ") but this application is '" + expectedEnvironmentId + "' (" + expectedTier
                + "); refusing to use it (LLD-12 §3)");
        this.expectedEnvironmentId = expectedEnvironmentId;
        this.expectedTier = expectedTier;
        this.foundEnvironmentId = foundEnvironmentId;
        this.foundTier = foundTier;
    }

    /** @return environment id of this application */
    public String expectedEnvironmentId() {
        return expectedEnvironmentId;
    }

    /** @return tier of this application */
    public String expectedTier() {
        return expectedTier;
    }

    /** @return environment id recorded in the store */
    public String foundEnvironmentId() {
        return foundEnvironmentId;
    }

    /** @return tier recorded in the store */
    public String foundTier() {
        return foundTier;
    }
}
