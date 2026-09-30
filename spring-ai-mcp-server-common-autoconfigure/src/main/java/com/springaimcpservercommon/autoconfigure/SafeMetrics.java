package com.springaimcpservercommon.autoconfigure;

import io.micrometer.core.instrument.Metrics;
import org.jspecify.annotations.NullMarked;

/**
 * Counter helper for optional Micrometer: {@code micrometer-core} is an optional dependency of this module, so
 * every call is guarded and metrics can never affect a turn or fail a bean.
 */
@NullMarked
final class SafeMetrics {

    private SafeMetrics() {
    }

    /**
     * Increments a counter in the global registry.
     *
     * @param name counter name
     * @param tags alternating tag keys and values
     */
    static void count(String name, String... tags) {
        try {
            Metrics.counter(name, tags).increment();
        } catch (LinkageError | RuntimeException e) {
            // metrics are optional; never affect the caller
        }
    }
}
