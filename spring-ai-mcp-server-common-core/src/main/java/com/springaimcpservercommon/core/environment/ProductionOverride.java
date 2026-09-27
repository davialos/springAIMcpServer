package com.springaimcpservercommon.core.environment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Break-glass production override (LLD-12 §2.3): enables listed capabilities under production rules until
 * {@code expiresAt}. Expiry is evaluated at call time, so the capability turns off without a restart.
 *
 * @param capabilities capabilities to enable (never {@link Capability#QUERY_PREVIEW})
 * @param expiresAt    mandatory expiry instant
 * @param reason       mandatory justification (incident/change id)
 */
public record ProductionOverride(Set<Capability> capabilities, Instant expiresAt, String reason) {

    /** Maximum distance between validation time and {@code expiresAt}. */
    public static final Duration MAX_DURATION = Duration.ofHours(72);

    /** Validates structural invariants and copies the set. */
    public ProductionOverride {
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("production override reason is mandatory");
        }
        if (capabilities.isEmpty()) {
            throw new IllegalArgumentException("production override must list at least one capability");
        }
        for (Capability c : capabilities) {
            if (!c.overridableInProduction()) {
                throw new IllegalArgumentException(c + " can never be enabled in production");
            }
        }
        capabilities = Set.copyOf(EnumSet.copyOf(capabilities));
    }

    /**
     * Creates an override and validates it against the current time: {@code expiresAt} must be at most
     * {@link #MAX_DURATION} ahead (checked at startup; an override that has already expired is accepted but
     * inactive).
     *
     * @param capabilities capabilities to enable
     * @param expiresAt    expiry
     * @param reason       justification
     * @param clock        clock for "now"
     * @return the validated override
     * @throws IllegalArgumentException if the override is invalid or too long
     */
    public static ProductionOverride validated(Set<Capability> capabilities, Instant expiresAt, String reason,
                                               Clock clock) {
        ProductionOverride override = new ProductionOverride(capabilities, expiresAt, reason);
        Instant latest = clock.instant().plus(MAX_DURATION);
        if (expiresAt.isAfter(latest)) {
            throw new IllegalArgumentException("production override expires-at must be at most "
                    + MAX_DURATION.toHours() + "h ahead");
        }
        return override;
    }

    /**
     * Whether the override is active at {@code now}.
     *
     * @param now current instant
     * @return {@code true} until {@code expiresAt} (exclusive)
     */
    public boolean activeAt(Instant now) {
        return now.isBefore(expiresAt);
    }

    /**
     * Whether the override enables a capability at {@code now}.
     *
     * @param capability capability
     * @param now        current instant
     * @return {@code true} if active and listed
     */
    public boolean enables(Capability capability, Instant now) {
        return activeAt(now) && capabilities.contains(capability);
    }
}
