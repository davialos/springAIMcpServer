package com.springaimcpservercommon.mockdata.simulation;

import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * What to simulate: who sends how many requests to which endpoint, for how long.
 *
 * @param target             absolute http(s) URL
 * @param method             HTTP method
 * @param headers            request headers (the content type of a payload defaults to JSON)
 * @param payload            produces the body of each request (an object is sent as JSON, a {@code String} as is); {@code null} = no body
 * @param virtualUsers       concurrent virtual threads, each sending requests back to back
 * @param duration           how long to run
 * @param rampUp             time over which the virtual users are started one after the other (zero = all at once)
 * @param maxRequestsPerSecond overall request rate cap, 0 = as fast as the target answers
 * @param requestTimeout     per-request timeout; a timeout counts as a failed request
 */
public record SimulationSpec(URI target, String method, Map<String, String> headers, @Nullable Supplier<?> payload,
                             int virtualUsers, Duration duration, Duration rampUp, int maxRequestsPerSecond,
                             Duration requestTimeout) {

    /** Validates and normalises. */
    public SimulationSpec {
        Objects.requireNonNull(target, "target");
        method = method == null ? "POST" : method.toUpperCase(java.util.Locale.ROOT);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        Objects.requireNonNull(duration, "duration");
        rampUp = rampUp == null ? Duration.ZERO : rampUp;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(10) : requestTimeout;
        if (virtualUsers < 1) {
            throw new IllegalArgumentException("virtualUsers must be at least 1");
        }
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("duration must be positive");
        }
        if (maxRequestsPerSecond < 0) {
            throw new IllegalArgumentException("maxRequestsPerSecond must not be negative");
        }
    }

    /**
     * A simple spec: POST of a payload, no ramp-up, no rate cap, 10 s timeout.
     *
     * @param target       the URL
     * @param payload      body supplier
     * @param virtualUsers concurrent virtual users
     * @param duration     run time
     * @return the spec
     */
    public static SimulationSpec post(URI target, @Nullable Supplier<?> payload, int virtualUsers, Duration duration) {
        return new SimulationSpec(target, "POST", Map.of(), payload, virtualUsers, duration, Duration.ZERO, 0, Duration.ofSeconds(10));
    }
}
