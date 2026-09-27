package com.springaimcpservercommon.persistence.usage;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Usage to add to one hourly bucket. {@code bucketStart} is truncated to the UTC hour on construction.
 *
 * @param bucketStart       any instant within the hour (truncated)
 * @param workspaceId       workspace
 * @param agentResourceId   agent, if the usage came from an agent turn
 * @param principalId       calling principal, if known
 * @param provider          model provider
 * @param model             model name
 * @param currency          currency of {@code costMicros}; {@code null} when the model is unpriced
 * @param calls             number of model calls
 * @param inputTokens       non-cached input tokens
 * @param outputTokens      output tokens
 * @param cachedInputTokens cached input tokens
 * @param costMicros        cost in micros of {@code currency} (0 when unpriced)
 */
public record UsageDelta(
        Instant bucketStart,
        UUID workspaceId,
        @Nullable UUID agentResourceId,
        @Nullable UUID principalId,
        String provider,
        String model,
        @Nullable String currency,
        int calls,
        long inputTokens,
        long outputTokens,
        long cachedInputTokens,
        long costMicros) {

    /** Validates and normalises the components. */
    public UsageDelta {
        Objects.requireNonNull(bucketStart, "bucketStart");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");
        bucketStart = bucketStart.truncatedTo(ChronoUnit.HOURS);
        if (currency != null) {
            Currencies.require(currency);
        }
        if (calls < 0 || inputTokens < 0 || outputTokens < 0 || cachedInputTokens < 0 || costMicros < 0) {
            throw new IllegalArgumentException("usage values must not be negative");
        }
        if (currency == null && costMicros != 0) {
            throw new IllegalArgumentException("a cost needs a currency");
        }
    }

    /**
     * Key of the bucket this delta belongs to ({@code uq_usage_hourly}).
     *
     * @return the bucket key
     */
    public BucketKey key() {
        return new BucketKey(bucketStart, workspaceId, agentResourceId, principalId, provider, model, currency);
    }

    /**
     * Adds another delta of the same bucket.
     *
     * @param other delta with an equal {@link #key()}
     * @return the sum
     */
    public UsageDelta plus(UsageDelta other) {
        if (!key().equals(other.key())) {
            throw new IllegalArgumentException("deltas of different buckets cannot be added");
        }
        return new UsageDelta(bucketStart, workspaceId, agentResourceId, principalId, provider, model, currency,
                Math.addExact(calls, other.calls), Math.addExact(inputTokens, other.inputTokens),
                Math.addExact(outputTokens, other.outputTokens),
                Math.addExact(cachedInputTokens, other.cachedInputTokens), Math.addExact(costMicros, other.costMicros));
    }

    /**
     * Unique key of an hourly bucket.
     *
     * @param bucketStart     hour start (UTC)
     * @param workspaceId     workspace
     * @param agentResourceId agent
     * @param principalId     principal
     * @param provider        provider
     * @param model           model
     * @param currency        currency
     */
    public record BucketKey(Instant bucketStart, UUID workspaceId, @Nullable UUID agentResourceId,
                            @Nullable UUID principalId, String provider, String model, @Nullable String currency) {
    }
}
