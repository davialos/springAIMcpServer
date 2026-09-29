package com.springaimcpservercommon.persistence.usage;

import java.time.Instant;
import java.util.Objects;

/**
 * Usage of one hourly bucket, as returned by {@link UsageLedger#hourlySeries}.
 *
 * @param bucketStart start of the UTC hour
 * @param totals      summed usage of that hour
 */
public record UsageBucket(Instant bucketStart, UsageTotals totals) {

    /** Validates the components. */
    public UsageBucket {
        Objects.requireNonNull(bucketStart, "bucketStart");
        Objects.requireNonNull(totals, "totals");
    }
}
