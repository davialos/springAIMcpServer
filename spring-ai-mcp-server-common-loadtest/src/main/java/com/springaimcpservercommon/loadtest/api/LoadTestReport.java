package com.springaimcpservercommon.loadtest.api;

import com.springaimcpservercommon.loadtest.discovery.Documents;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A finished run's report, read from the {@code reports/<mode>-<timestamp>.json} file the suite's
 * {@code handleSummary} writes.
 *
 * @param file             the report file
 * @param mode             load mode ({@code smoke}, {@code mixed-spike} …)
 * @param dataMode         data mode
 * @param baseUrl          target
 * @param total            all requests together
 * @param apis             per API, in suite order
 * @param failedThresholds failed threshold rules ({@code http_req_duration{api:getOrder}: p(95)<500})
 */
public record LoadTestReport(Path file, String mode, String dataMode, String baseUrl, Stats total,
                             List<ApiStats> apis, List<String> failedThresholds) {

    /** Compact constructor: defensive copies. */
    public LoadTestReport {
        apis = List.copyOf(apis);
        failedThresholds = List.copyOf(failedThresholds);
    }

    /**
     * Totals of a run.
     *
     * @param requests   requests sent
     * @param rps        requests per second
     * @param failedRate share of failed requests (0..1), or {@code null} if none were sent
     * @param p95Ms      95th percentile duration in ms, or {@code null}
     */
    public record Stats(long requests, double rps, @Nullable Double failedRate, @Nullable Double p95Ms) {
    }

    /**
     * One API's numbers.
     *
     * @param api        API id
     * @param name       endpoint ({@code GET /orders/{id}})
     * @param requests   requests sent
     * @param rps        requests per second, or {@code null}
     * @param failedRate share of failed requests, or {@code null}
     * @param avgMs      mean duration, or {@code null}
     * @param p95Ms      95th percentile, or {@code null}
     * @param p99Ms      99th percentile, or {@code null}
     * @param maxMs      maximum, or {@code null}
     */
    public record ApiStats(String api, String name, long requests, @Nullable Double rps, @Nullable Double failedRate,
                           @Nullable Double avgMs, @Nullable Double p95Ms, @Nullable Double p99Ms,
                           @Nullable Double maxMs) {
    }

    /**
     * Whether every threshold passed.
     *
     * @return {@code true} if none failed
     */
    public boolean thresholdsPassed() {
        return failedThresholds.isEmpty();
    }

    /**
     * One API's numbers.
     *
     * @param api API id
     * @return the stats, if the API was part of the run
     */
    public Optional<ApiStats> api(String api) {
        return apis.stream().filter(a -> a.api().equals(api)).findFirst();
    }

    /**
     * Reads a report file.
     *
     * @param file {@code reports/<mode>-<timestamp>.json}
     * @return the report
     * @throws UncheckedIOException if it cannot be read
     * @throws IllegalArgumentException if it is not a suite report
     */
    public static LoadTestReport read(Path file) {
        JsonNode n;
        try {
            n = Documents.parse(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (!n.has("apis") || !n.has("mode")) {
            throw new IllegalArgumentException(file + " is not a load-test report (no mode/apis)");
        }
        List<ApiStats> apis = new ArrayList<>();
        for (JsonNode a : n.path("apis")) {
            apis.add(new ApiStats(a.path("api").asString(), a.path("name").asString(""),
                    a.path("requests").asLong(0), number(a, "rps"), number(a, "failed"), number(a, "avg"),
                    number(a, "p95"), number(a, "p99"), number(a, "max")));
        }
        List<String> failed = new ArrayList<>();
        n.path("failedThresholds").forEach(t -> failed.add(t.asString()));
        JsonNode m = n.path("metrics");
        JsonNode reqs = m.path("http_reqs").path("values");
        Stats total = new Stats(reqs.path("count").asLong(apis.stream().mapToLong(ApiStats::requests).sum()),
                reqs.path("rate").asDouble(0), number(m.path("http_req_failed").path("values"), "rate"),
                number(m.path("http_req_duration").path("values"), "p(95)"));
        return new LoadTestReport(file, n.path("mode").asString(), n.path("dataMode").asString(""),
                n.path("baseUrl").asString(""), total, apis, failed);
    }

    /**
     * The newest report of a suite, optionally of one mode.
     *
     * @param suiteDir suite directory
     * @param mode     load mode, or {@code null} for any
     * @return the newest report, if any
     */
    public static Optional<LoadTestReport> latest(Path suiteDir, @Nullable String mode) {
        return reports(suiteDir, mode).stream().findFirst().map(LoadTestReport::read);
    }

    /**
     * Report files of a suite, newest first.
     *
     * @param suiteDir suite directory
     * @param mode     load mode, or {@code null} for any
     * @return files
     */
    public static List<Path> reports(Path suiteDir, @Nullable String mode) {
        Path dir = suiteDir.resolve("reports");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            // names are <mode>-<ISO timestamp>.json: newest last within a mode; sort by the timestamp suffix
            return files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> mode == null || p.getFileName().toString().matches(
                            java.util.regex.Pattern.quote(mode) + "-\\d{4}-.*\\.json"))
                    .sorted(Comparator.comparing(LoadTestReport::stamp).reversed())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String stamp(Path p) {
        String name = p.getFileName().toString();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{4}-\\d{2}-\\d{2}T.*)\\.json$")
                .matcher(name);
        return m.find() ? m.group(1) : name;
    }

    private static @Nullable Double number(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isNumber() ? v.asDouble() : null;
    }
}
