package com.springaimcpservercommon.mockdata.simulation;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Result of a simulation. Contains counts and latencies only — never request or response data.
 *
 * @param requests       requests sent
 * @param successes      requests answered with 2xx or 3xx
 * @param failures       everything else (4xx, 5xx, timeout, connection error, payload error)
 * @param outcomes       count per outcome: {@code 2xx}, {@code 3xx}, {@code 4xx}, {@code 5xx}, {@code timeout}, {@code error:<ExceptionClass>}, {@code payload-error}
 * @param elapsed        wall-clock time of the run
 * @param requestsPerSecond requests divided by elapsed seconds
 * @param p50Micros      median latency, microseconds (about 3 % resolution)
 * @param p90Micros      90th percentile
 * @param p95Micros      95th percentile
 * @param p99Micros      99th percentile
 * @param maxMicros      slowest request
 * @param errorSamples   up to five distinct failure descriptions (status codes and exception class names)
 * @param cancelled      whether the run was stopped before its duration ended
 */
public record SimulationReport(long requests, long successes, long failures, Map<String, Long> outcomes, Duration elapsed,
                               double requestsPerSecond, long p50Micros, long p90Micros, long p95Micros, long p99Micros,
                               long maxMicros, List<String> errorSamples, boolean cancelled) {

    /** Defensive copies. */
    public SimulationReport {
        outcomes = Map.copyOf(outcomes);
        errorSamples = List.copyOf(errorSamples);
    }

    /**
     * Share of failed requests.
     *
     * @return 0..1, 0 when nothing was sent
     */
    public double errorRate() {
        return requests == 0 ? 0 : (double) failures / requests;
    }
}
