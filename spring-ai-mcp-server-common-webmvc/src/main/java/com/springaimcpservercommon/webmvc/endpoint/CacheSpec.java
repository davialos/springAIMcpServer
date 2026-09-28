package com.springaimcpservercommon.webmvc.endpoint;

import java.time.Duration;
import java.util.Objects;

/**
 * Response-cache configuration for a dynamic endpoint (LLD-04 §2).
 *
 * <p>Cache key always includes the principal's policy fingerprint to prevent cross-principal leaks.
 *
 * @param enabled whether caching is active
 * @param ttl     time-to-live for a cached response
 * @param varyByParams additional request parameter names to include in the cache key
 */
public record CacheSpec(boolean enabled, Duration ttl, java.util.List<String> varyByParams) {

    /** Caching disabled. */
    public static final CacheSpec DISABLED = new CacheSpec(false, Duration.ZERO, java.util.List.of());

    /** Validates. */
    public CacheSpec {
        Objects.requireNonNull(ttl, "ttl");
        Objects.requireNonNull(varyByParams, "varyByParams");
        varyByParams = java.util.List.copyOf(varyByParams);
        if (enabled && ttl.isNegative()) throw new IllegalArgumentException("ttl must be >= 0 when enabled");
    }
}
