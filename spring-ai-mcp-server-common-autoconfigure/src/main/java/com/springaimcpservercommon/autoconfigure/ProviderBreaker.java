package com.springaimcpservercommon.autoconfigure;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A small circuit breaker for one model provider (release-it). After {@code failureThreshold} consecutive failures it
 * opens and refuses calls for {@code openFor}; then it lets one probe through (half-open): success closes it, failure
 * re-opens it. State is per node and soft by design: it protects this node's threads from a struggling provider and
 * needs no shared store (ADR-0021).
 */
@NullMarked
final class ProviderBreaker {

    private final int failureThreshold;
    private final Duration openFor;
    private final Clock clock;

    private int consecutiveFailures;
    private Instant openedAt = Instant.MIN;
    private boolean open;
    private boolean probeInFlight;

    ProviderBreaker(int failureThreshold, Duration openFor, Clock clock) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be >= 1");
        }
        if (openFor.isNegative() || openFor.isZero()) {
            throw new IllegalArgumentException("openFor must be positive");
        }
        this.failureThreshold = failureThreshold;
        this.openFor = Objects.requireNonNull(openFor, "openFor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** @return {@code true} if a call may go to the provider now; a half-open breaker admits one probe at a time */
    synchronized boolean tryAcquire() {
        if (!open) {
            return true;
        }
        if (clock.instant().isBefore(openedAt.plus(openFor)) || probeInFlight) {
            return false;
        }
        probeInFlight = true;
        return true;
    }

    /** Records a successful call: the breaker closes. */
    synchronized void onSuccess() {
        consecutiveFailures = 0;
        open = false;
        probeInFlight = false;
    }

    /** Records a failed call: enough in a row (or a failed probe) opens the breaker. */
    synchronized void onFailure() {
        consecutiveFailures++;
        if (open || consecutiveFailures >= failureThreshold) {
            open = true;
            openedAt = clock.instant();
        }
        probeInFlight = false;
    }

    /**
     * A point-in-time view of the breaker, for operators.
     *
     * @param state               {@code CLOSED}, {@code OPEN} (refusing) or {@code HALF_OPEN} (next call is a probe)
     * @param consecutiveFailures failures since the last success
     * @param openedAt            when the breaker last opened, {@code null} while closed
     * @param retryAt             when an open breaker admits its probe, {@code null} while closed
     */
    record State(String state, int consecutiveFailures, @Nullable Instant openedAt, @Nullable Instant retryAt) {}

    /** @return the current state */
    synchronized State state() {
        if (!open) {
            return new State("CLOSED", consecutiveFailures, null, null);
        }
        Instant retryAt = openedAt.plus(openFor);
        String state = clock.instant().isBefore(retryAt) || probeInFlight ? "OPEN" : "HALF_OPEN";
        return new State(state, consecutiveFailures, openedAt, retryAt);
    }

    /** Closes the breaker by hand (an operator knows the provider is back). */
    synchronized void reset() {
        onSuccess();
    }

    /** @return {@code true} while calls are being refused or only probed */
    synchronized boolean isOpen() {
        return open;
    }
}
