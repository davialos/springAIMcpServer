package com.springaimcpservercommon.loadtest.observe;

import com.springaimcpservercommon.loadtest.observe.PrometheusText.Sample;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Judges the system under test from what its own Prometheus endpoint showed during the run. A load test that only
 * reads the client side can pass while the database pool queues requests, the JVM spends a tenth of its time in GC or
 * threads pile up — these checks make those fail the run. Every check is skipped (not failed) when the metric is
 * absent, so a service without HikariCP or Tomcat simply has fewer checks.
 */
public final class ServerChecks {

    private ServerChecks() {
    }

    /** How serious a finding is. */
    public enum Level {
        /** Fails the run. */
        FAIL,
        /** Reported; does not fail the run. */
        WARN,
        /** Within the limit. */
        OK
    }

    /**
     * One check's result.
     *
     * @param id      check id ({@code gc-share})
     * @param level   outcome
     * @param message what was measured against which limit
     */
    public record Finding(String id, Level level, String message) {
    }

    /**
     * One scrape of the metrics endpoint.
     *
     * @param at      when
     * @param samples the samples
     */
    public record Snapshot(Instant at, List<Sample> samples) {
    }

    /**
     * Limits. A value of {@code 0} for a ratio turns the "hard" check off for that metric.
     *
     * @param hikariPendingShare   fail when requests waited for a connection in more than this share of the samples
     * @param hikariSaturation     warn when active/max connections reach this
     * @param gcShare              fail when GC pauses took more than this share of the wall time
     * @param threadSaturation     warn when busy/max request threads reach this
     * @param heapUsage            warn when the heap reaches this share of its maximum
     * @param cpu                  warn when process CPU reaches this
     * @param failOnServerErrors   fail when the server counted any 5xx response
     * @param failOnLogErrors      fail when the application logged any error
     */
    public record Settings(double hikariPendingShare, double hikariSaturation, double gcShare,
                           double threadSaturation, double heapUsage, double cpu, boolean failOnServerErrors,
                           boolean failOnLogErrors) {

        /**
         * The defaults.
         *
         * @return settings
         */
        public static Settings defaults() {
            return new Settings(0.2, 0.95, 0.05, 0.95, 0.9, 0.9, true, true);
        }

        /**
         * Reads {@code loadtest.config.json → serverChecks}; missing keys keep their default.
         *
         * @param node the block, or {@code null}
         * @return settings
         */
        public static Settings from(@Nullable JsonNode node) {
            Settings d = defaults();
            if (node == null || node.isMissingNode()) {
                return d;
            }
            return new Settings(node.path("hikariPendingShare").asDouble(d.hikariPendingShare()),
                    node.path("hikariSaturation").asDouble(d.hikariSaturation()),
                    node.path("gcShare").asDouble(d.gcShare()),
                    node.path("threadSaturation").asDouble(d.threadSaturation()),
                    node.path("heapUsage").asDouble(d.heapUsage()), node.path("cpu").asDouble(d.cpu()),
                    node.path("failOnServerErrors").asBoolean(d.failOnServerErrors()),
                    node.path("failOnLogErrors").asBoolean(d.failOnLogErrors()));
        }
    }

    /**
     * Evaluates the checks over the scrapes taken before, during and after the run.
     *
     * @param snapshots scrapes in time order
     * @param s         the limits
     * @return one finding per check that had data
     */
    public static List<Finding> evaluate(List<Snapshot> snapshots, Settings s) {
        List<Finding> out = new ArrayList<>();
        if (snapshots.size() < 2) {
            return out;
        }
        Snapshot first = snapshots.getFirst();
        Snapshot last = snapshots.getLast();
        double seconds = Math.max(1, Duration.between(first.at(), last.at()).toMillis() / 1000.0);

        // HikariCP: requests waiting for a connection are the first sign of a pool that is too small or a slow database
        if (has(snapshots, "hikaricp_connections_pending") && s.hikariPendingShare() > 0) {
            long waiting = snapshots.stream().filter(n -> sum(n, "hikaricp_connections_pending") > 0).count();
            double share = (double) waiting / snapshots.size();
            double peak = snapshots.stream().mapToDouble(n -> sum(n, "hikaricp_connections_pending")).max().orElse(0);
            out.add(finding("hikari-pending", share > s.hikariPendingShare() ? Level.FAIL : Level.OK,
                    "threads waited for a database connection in " + pct(share) + " of the samples (peak " + (int) peak
                            + "), limit " + pct(s.hikariPendingShare())));
        }
        if (has(snapshots, "hikaricp_connections_active") && has(snapshots, "hikaricp_connections_max")) {
            double peak = snapshots.stream().mapToDouble(n -> ratio(sum(n, "hikaricp_connections_active"),
                    sum(n, "hikaricp_connections_max"))).max().orElse(0);
            out.add(finding("hikari-saturation", peak >= s.hikariSaturation() ? Level.WARN : Level.OK,
                    "connection pool " + pct(peak) + " in use at its busiest, warning at " + pct(s.hikariSaturation())));
        }
        // GC: time the JVM stood still
        if (has(snapshots, "jvm_gc_pause_seconds_sum") && s.gcShare() > 0) {
            double paused = Math.max(0, sum(last, "jvm_gc_pause_seconds_sum") - sum(first, "jvm_gc_pause_seconds_sum"));
            double share = paused / seconds;
            out.add(finding("gc-share", share > s.gcShare() ? Level.FAIL : Level.OK,
                    "GC pauses took " + pct(share) + " of the wall time (" + String.format(Locale.ROOT, "%.1f", paused)
                            + " s of " + (int) seconds + " s), limit " + pct(s.gcShare())));
        }
        // request threads
        if (has(snapshots, "tomcat_threads_busy_threads") && has(snapshots, "tomcat_threads_config_max_threads")) {
            double peak = snapshots.stream().mapToDouble(n -> ratio(sum(n, "tomcat_threads_busy_threads"),
                    sum(n, "tomcat_threads_config_max_threads"))).max().orElse(0);
            out.add(finding("tomcat-threads", peak >= s.threadSaturation() ? Level.WARN : Level.OK,
                    "request threads " + pct(peak) + " busy at the peak, warning at " + pct(s.threadSaturation())));
        }
        // server-side errors
        if (has(snapshots, "http_server_requests_seconds_count")) {
            double errors = Math.max(0, sum(last, "http_server_requests_seconds_count", st5xx())
                    - sum(first, "http_server_requests_seconds_count", st5xx()));
            out.add(finding("server-5xx", errors > 0 ? (s.failOnServerErrors() ? Level.FAIL : Level.WARN) : Level.OK,
                    (long) errors + " requests answered 5xx (counted by the server)"));
        }
        if (has(snapshots, "logback_events_total")) {
            double errors = Math.max(0, sum(last, "logback_events_total", l -> "error".equals(l.get("level")))
                    - sum(first, "logback_events_total", l -> "error".equals(l.get("level"))));
            out.add(finding("log-errors", errors > 0 ? (s.failOnLogErrors() ? Level.FAIL : Level.WARN) : Level.OK,
                    (long) errors + " errors logged by the application"));
        }
        // JVM health
        if (has(snapshots, "jvm_memory_used_bytes") && has(snapshots, "jvm_memory_max_bytes")) {
            Predicate<java.util.Map<String, String>> heap = l -> "heap".equals(l.get("area"));
            double peak = snapshots.stream().mapToDouble(n -> ratio(sum(n, "jvm_memory_used_bytes", heap),
                    sumPositive(n, "jvm_memory_max_bytes", heap))).max().orElse(0);
            out.add(finding("heap-usage", peak >= s.heapUsage() ? Level.WARN : Level.OK,
                    "heap " + pct(peak) + " full at the peak, warning at " + pct(s.heapUsage())));
        }
        if (has(snapshots, "process_cpu_usage")) {
            double peak = snapshots.stream().mapToDouble(n -> sum(n, "process_cpu_usage")).max().orElse(0);
            out.add(finding("cpu", peak >= s.cpu() ? Level.WARN : Level.OK,
                    "process CPU " + pct(peak) + " at the peak, warning at " + pct(s.cpu())
                            + (peak >= s.cpu() ? " (the service is CPU-bound: more load only queues)" : "")));
        }
        if (has(snapshots, "jvm_threads_live_threads")) {
            double before = sum(first, "jvm_threads_live_threads");
            double after = sum(last, "jvm_threads_live_threads");
            boolean grew = after > before * 1.5 && after - before > 50;
            out.add(finding("threads-growth", grew ? Level.WARN : Level.OK,
                    "live threads " + (int) before + " -> " + (int) after + (grew ? " (leaking threads?)" : "")));
        }
        return out;
    }

    /**
     * Whether any finding fails the run.
     *
     * @param findings the findings
     * @return {@code true} if one has level {@link Level#FAIL}
     */
    public static boolean failed(List<Finding> findings) {
        return findings.stream().anyMatch(f -> f.level() == Level.FAIL);
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────────

    private static Finding finding(String id, Level level, String message) {
        return new Finding(id, level, message);
    }

    private static Predicate<java.util.Map<String, String>> st5xx() {
        return l -> l.getOrDefault("status", "").startsWith("5");
    }

    private static boolean has(List<Snapshot> snapshots, String name) {
        return snapshots.stream().anyMatch(n -> n.samples().stream().anyMatch(s -> s.name().equals(name)));
    }

    private static double sum(Snapshot n, String name) {
        return sum(n, name, l -> true);
    }

    private static double sum(Snapshot n, String name, Predicate<java.util.Map<String, String>> labels) {
        double total = 0;
        for (Sample s : n.samples()) {
            if (s.name().equals(name) && labels.test(s.labels()) && Double.isFinite(s.value())) {
                total += s.value();
            }
        }
        return total;
    }

    /** A memory pool without a maximum reports -1: it must not shrink the total. */
    private static double sumPositive(Snapshot n, String name, Predicate<java.util.Map<String, String>> labels) {
        double total = 0;
        for (Sample s : n.samples()) {
            if (s.name().equals(name) && labels.test(s.labels()) && s.value() > 0) {
                total += s.value();
            }
        }
        return total;
    }

    private static double ratio(double a, double b) {
        return b <= 0 ? 0 : a / b;
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.0f%%", v * 100);
    }
}
