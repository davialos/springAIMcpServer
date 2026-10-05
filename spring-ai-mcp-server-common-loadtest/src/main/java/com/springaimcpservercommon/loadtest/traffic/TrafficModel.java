package com.springaimcpservercommon.loadtest.traffic;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What production traffic looked like, mapped onto the suite's APIs.
 *
 * @param totalRequests requests that matched an API
 * @param unmatched     requests that matched none (static files, other services, unknown paths)
 * @param averageRate   requests per second on average, or {@code 0} when unknown (metrics without a period)
 * @param peakRate      requests per second in the busiest minute, or {@code 0} when unknown
 * @param apis          per API: share, error rates and latency
 * @param entry         session start distribution: API id → probability (logs only)
 * @param transitions   API id → next API id (or {@code $end}) → probability (logs only)
 * @param sessions      sessions seen (logs only)
 */
public record TrafficModel(long totalRequests, long unmatched, double averageRate, double peakRate,
                           Map<String, ApiTraffic> apis, Map<String, Double> entry,
                           Map<String, Map<String, Double>> transitions, int sessions) {

    /**
     * One API's observed traffic.
     *
     * @param requests        requests seen
     * @param share           fraction of all matched requests
     * @param serverErrorRate fraction answered 5xx
     * @param clientErrorRate fraction answered 4xx
     * @param meanMs          mean latency in ms, or {@code null}
     * @param p95Ms           95th percentile latency in ms (histogram buckets or log durations), or {@code null}
     */
    public record ApiTraffic(long requests, double share, double serverErrorRate, double clientErrorRate,
                             @Nullable Double meanMs, @Nullable Double p95Ms) {
    }

    /** Compact constructor: ordered, unmodifiable copies. */
    public TrafficModel {
        apis = Collections.unmodifiableMap(new LinkedHashMap<>(apis));
        entry = Collections.unmodifiableMap(new LinkedHashMap<>(entry));
        transitions = Collections.unmodifiableMap(new LinkedHashMap<>(transitions));
    }

    /**
     * Fraction of requests that matched an API.
     *
     * @return {@code 0..1}
     */
    public double coverage() {
        long all = totalRequests + unmatched;
        return all == 0 ? 0 : (double) totalRequests / all;
    }
}
