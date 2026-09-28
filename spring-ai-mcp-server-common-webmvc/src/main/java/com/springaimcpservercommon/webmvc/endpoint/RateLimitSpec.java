package com.springaimcpservercommon.webmvc.endpoint;

import java.time.Duration;

/**
 * Rate-limit configuration for a dynamic endpoint (LLD-04 §2).
 *
 * @param requestsPerMinute maximum requests per principal per minute; 0 = unlimited
 * @param burstSize         maximum burst capacity (token-bucket); 0 = requestsPerMinute
 * @param workspaceLimit    workspace-wide requests per minute across all principals; 0 = unlimited
 */
public record RateLimitSpec(int requestsPerMinute, int burstSize, int workspaceLimit) {

    /** No rate limiting. */
    public static final RateLimitSpec UNLIMITED = new RateLimitSpec(0, 0, 0);

    /** Validates. */
    public RateLimitSpec {
        if (requestsPerMinute < 0) throw new IllegalArgumentException("requestsPerMinute must be >= 0");
        if (burstSize < 0) throw new IllegalArgumentException("burstSize must be >= 0");
        if (workspaceLimit < 0) throw new IllegalArgumentException("workspaceLimit must be >= 0");
    }

    /** Effective burst: if zero, equals requestsPerMinute. */
    public int effectiveBurst() {
        return burstSize == 0 ? requestsPerMinute : burstSize;
    }
}
