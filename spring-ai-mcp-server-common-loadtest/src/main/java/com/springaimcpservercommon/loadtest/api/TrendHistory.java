package com.springaimcpservercommon.loadtest.api;

import com.springaimcpservercommon.loadtest.discovery.Documents;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The performance history of a suite: one line per run in a JSON-Lines file ({@code history/<suite>.jsonl}, kept by
 * CI between builds), so a slow drift — p95 creeping up 3% a build — shows up although no single build regressed against
 * its baseline.
 */
public final class TrendHistory {

    /** Sparkline blocks, lowest to highest. */
    private static final String BLOCKS = "▁▂▃▄▅▆▇█";
    /** How many of the previous runs the latest one is compared with. */
    private static final int WINDOW = 10;

    private TrendHistory() {
    }

    /**
     * One recorded run.
     *
     * @param at         when it was recorded (ISO-8601)
     * @param commit     the commit it ran on, or empty
     * @param mode       load mode
     * @param report     the report file's name
     * @param requests   requests sent
     * @param rps        requests per second
     * @param failedRate failed share, or {@code null}
     * @param p95Ms      p95 duration in ms, or {@code null}
     * @param passed     whether every threshold passed
     * @param apiP95     p95 per API
     */
    public record Point(String at, String commit, String mode, String report, long requests, double rps,
                        @Nullable Double failedRate, @Nullable Double p95Ms, boolean passed, Map<String, Double> apiP95) {

        /** Compact constructor: defensive copy. */
        public Point {
            apiP95 = Map.copyOf(apiP95);
        }
    }

    /**
     * The commit a CI system is building.
     *
     * @param env the environment ({@code GITHUB_SHA}, {@code CI_COMMIT_SHA}, {@code GIT_COMMIT} …)
     * @return the commit, or empty
     */
    public static String commitFrom(Map<String, String> env) {
        for (String name : List.of("GITHUB_SHA", "CI_COMMIT_SHA", "GIT_COMMIT", "BUILD_VCS_NUMBER")) {
            String v = env.get(name);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    /**
     * Summarises a report as a history point.
     *
     * @param report the run
     * @param commit its commit, or {@code null}
     * @param at     when to stamp it
     * @return the point
     */
    public static Point point(LoadTestReport report, @Nullable String commit, Instant at) {
        Map<String, Double> p95 = new LinkedHashMap<>();
        for (LoadTestReport.ApiStats a : report.apis()) {
            if (a.p95Ms() != null && a.requests() > 0) {
                p95.put(a.api(), a.p95Ms());
            }
        }
        return new Point(at.toString(), commit == null ? "" : commit, report.mode(),
                report.file().getFileName().toString(), report.total().requests(), report.total().rps(),
                report.total().failedRate(), report.total().p95Ms(), report.thresholdsPassed(), p95);
    }

    /**
     * Appends a run to the history unless that report file is already in it.
     *
     * @param file   the {@code .jsonl} history file (created with its directory)
     * @param report the run
     * @param commit its commit, or {@code null}
     * @param at     the stamp
     * @return {@code true} when a line was added
     */
    public static boolean record(Path file, LoadTestReport report, @Nullable String commit, Instant at) {
        Point p = point(report, commit, at);
        for (Point existing : read(file, null, Integer.MAX_VALUE)) {
            if (existing.report().equals(p.report())) { // a report file is one run, whoever records it
                return false;
            }
        }
        ObjectNode line = Documents.json().createObjectNode();
        line.put("at", p.at()).put("commit", p.commit()).put("mode", p.mode()).put("report", p.report())
                .put("requests", p.requests()).put("rps", p.rps());
        if (p.failedRate() != null) {
            line.put("failedRate", p.failedRate());
        }
        if (p.p95Ms() != null) {
            line.put("p95Ms", p.p95Ms());
        }
        line.put("passed", p.passed());
        ObjectNode apis = line.putObject("apiP95");
        p.apiP95().forEach(apis::put);
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return true;
    }

    /**
     * Reads the history, oldest first.
     *
     * @param file the {@code .jsonl} file; a missing file is an empty history
     * @param mode only this load mode, or {@code null} for all
     * @param last the most recent this many (use {@link Integer#MAX_VALUE} for all)
     * @return points
     */
    public static List<Point> read(Path file, @Nullable String mode, int last) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<Point> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode n;
                try {
                    n = Documents.parse(line);
                } catch (RuntimeException e) {
                    continue; // a torn line must not lose the rest of the history
                }
                if (mode != null && !mode.equals(n.path("mode").asString(""))) {
                    continue;
                }
                Map<String, Double> apis = new LinkedHashMap<>();
                n.path("apiP95").properties().forEach(e -> apis.put(e.getKey(), e.getValue().asDouble()));
                out.add(new Point(n.path("at").asString(""), n.path("commit").asString(""), n.path("mode").asString(""),
                        n.path("report").asString(""), n.path("requests").asLong(0), n.path("rps").asDouble(0),
                        n.path("failedRate").isNumber() ? n.path("failedRate").asDouble() : null,
                        n.path("p95Ms").isNumber() ? n.path("p95Ms").asDouble() : null, n.path("passed").asBoolean(true),
                        apis));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.size() <= last ? out : List.copyOf(out.subList(out.size() - last, out.size()));
    }

    /**
     * A one-line chart.
     *
     * @param values the series (nulls are gaps)
     * @return one block per value, scaled between the series' minimum and maximum
     */
    public static String sparkline(List<@Nullable Double> values) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (Double v : values) {
            if (v != null) {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        StringBuilder s = new StringBuilder();
        for (Double v : values) {
            if (v == null) {
                s.append('·');
            } else if (max - min < 1e-9) {
                s.append(BLOCKS.charAt(BLOCKS.length() / 2));
            } else {
                s.append(BLOCKS.charAt((int) Math.round((v - min) / (max - min) * (BLOCKS.length() - 1))));
            }
        }
        return s.toString();
    }

    /**
     * How much the newest run's p95 moved against the median of the runs before it.
     *
     * @param points oldest first
     * @return the change in percent, or {@code null} with fewer than three earlier runs
     */
    public static @Nullable Double p95Drift(List<Point> points) {
        if (points.size() < 4) {
            return null;
        }
        List<Double> before = new ArrayList<>();
        int from = Math.max(0, points.size() - 1 - WINDOW);
        for (Point p : points.subList(from, points.size() - 1)) {
            if (p.p95Ms() != null) {
                before.add(p.p95Ms());
            }
        }
        Double now = points.get(points.size() - 1).p95Ms();
        if (before.size() < 3 || now == null) {
            return null;
        }
        before.sort(Double::compare);
        double median = before.get(before.size() / 2);
        return median <= 0 ? null : (now - median) / median * 100;
    }

    /**
     * The history as Markdown: a chart of p95 / throughput / errors over the runs, the newest runs in a table and a
     * note when the newest p95 sits well above its recent median.
     *
     * @param points oldest first
     * @return Markdown, empty without points
     */
    public static String toMarkdown(List<Point> points) {
        if (points.isEmpty()) {
            return "";
        }
        StringBuilder md = new StringBuilder("### Trend (last " + points.size() + " runs)\n\n");
        md.append("| | oldest → newest |\n|---|---|\n");
        md.append("| p95 ms | `").append(sparkline(points.stream().map(Point::p95Ms).toList())).append("` ")
                .append(ms(points.get(points.size() - 1).p95Ms())).append(" |\n");
        md.append("| req/s | `").append(sparkline(points.stream().map(p -> (Double) p.rps()).toList())).append("` ")
                .append(String.format(Locale.ROOT, "%.1f", points.get(points.size() - 1).rps())).append(" |\n");
        md.append("| failed | `").append(sparkline(points.stream().map(Point::failedRate).toList())).append("` ")
                .append(pct(points.get(points.size() - 1).failedRate())).append(" |\n\n");
        Double drift = p95Drift(points);
        if (drift != null && drift > 10) {
            md.append(String.format(Locale.ROOT, "> p95 is **%.0f%% above** the median of the previous runs — a slow "
                    + "drift that no single baseline comparison flags.\n\n", drift));
        }
        md.append("| run | commit | p95 ms | req/s | failed | thresholds |\n|---|---|---:|---:|---:|---|\n");
        List<Point> newest = points.subList(Math.max(0, points.size() - 8), points.size());
        for (int i = newest.size() - 1; i >= 0; i--) {
            Point p = newest.get(i);
            md.append("| ").append(p.at().length() >= 16 ? p.at().substring(0, 16).replace('T', ' ') : p.at())
                    .append(" | `").append(p.commit().length() > 8 ? p.commit().substring(0, 8) : p.commit())
                    .append("` | ").append(ms(p.p95Ms())).append(" | ")
                    .append(String.format(Locale.ROOT, "%.1f", p.rps())).append(" | ").append(pct(p.failedRate()))
                    .append(" | ").append(p.passed() ? "passed" : "**failed**").append(" |\n");
        }
        return md.append('\n').toString();
    }

    private static String ms(@Nullable Double v) {
        return v == null ? "-" : String.format(Locale.ROOT, "%.1f", v);
    }

    private static String pct(@Nullable Double v) {
        return v == null ? "-" : String.format(Locale.ROOT, "%.2f%%", v * 100);
    }
}
