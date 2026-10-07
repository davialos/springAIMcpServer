package com.springaimcpservercommon.loadtest.api;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a CI job shows about a load-test run: a Markdown comment for the pull request (baseline comparison + trend),
 * the same text appended to the job summary ({@code $GITHUB_STEP_SUMMARY}), the comparison and the history files the
 * pipeline keeps between builds.
 */
public final class CiReport {

    /** First line of the comment: lets a pipeline find and update its previous comment instead of adding another. */
    public static final String MARKER = "<!-- saimcp-loadtest -->";

    private CiReport() {
    }

    /**
     * Where {@link #publish} wrote.
     *
     * @param comment    {@code reports/pr-comment.md}
     * @param comparison {@code reports/comparison-<mode>.md}, or {@code null} without a baseline
     * @param summary    whether the text was appended to the job summary file
     * @param recorded   whether the run was added to the history
     */
    public record Published(Path comment, @Nullable Path comparison, boolean summary, boolean recorded) {
    }

    /**
     * The pull-request comment.
     *
     * @param current    the run
     * @param comparison the comparison with the baseline, or {@code null}
     * @param trend      the history, oldest first (may be empty)
     * @return Markdown starting with {@link #MARKER}
     */
    public static String comment(LoadTestReport current, @Nullable ReportComparison comparison,
                                 List<TrendHistory.Point> trend) {
        boolean regressed = comparison != null && !comparison.passed();
        boolean ok = current.thresholdsPassed() && !regressed;
        StringBuilder md = new StringBuilder(MARKER).append('\n');
        md.append("## ").append(ok ? "✅" : "❌").append(" Load test — ").append(current.mode()).append("\n\n");
        LoadTestReport.Stats t = current.total();
        md.append(String.format(Locale.ROOT, "%d requests, %.1f req/s, failed %s, p95 %s ms against `%s`\n\n",
                t.requests(), t.rps(), t.failedRate() == null ? "-" : String.format(Locale.ROOT, "%.2f%%",
                        t.failedRate() * 100), t.p95Ms() == null ? "-" : String.format(Locale.ROOT, "%.1f", t.p95Ms()),
                current.baseUrl()));
        if (!current.thresholdsPassed()) {
            md.append("**Thresholds failed:**\n\n");
            current.failedThresholds().forEach(f -> md.append("- `").append(f).append("`\n"));
            md.append('\n');
        }
        if (comparison != null) {
            md.append(comparison.toMarkdown().replaceFirst("^# ", "### ")).append('\n');
        } else {
            md.append("_No baseline yet: the first run on the main branch becomes it._\n\n");
        }
        md.append(TrendHistory.toMarkdown(trend));
        return md.toString();
    }

    /**
     * Writes the CI outputs of a run.
     *
     * @param suite       suite directory ({@code reports/} receives the files)
     * @param current     the run
     * @param comparison  the comparison with the baseline, or {@code null}
     * @param history     the history file to add the run to and read the trend from, or {@code null}
     * @param env         the environment: commit variables and {@code GITHUB_STEP_SUMMARY}
     * @return what was written
     */
    public static Published publish(Path suite, LoadTestReport current, @Nullable ReportComparison comparison,
                                    @Nullable Path history, Map<String, String> env) {
        boolean recorded = false;
        List<TrendHistory.Point> trend = List.of();
        if (history != null) {
            recorded = TrendHistory.record(history, current, TrendHistory.commitFrom(env), Instant.now());
            trend = TrendHistory.read(history, current.mode(), 20);
        }
        String comment = comment(current, comparison, trend);
        Path reports = suite.resolve("reports");
        Path commentFile = reports.resolve("pr-comment.md");
        Path comparisonFile = null;
        boolean summary = false;
        try {
            Files.createDirectories(reports);
            Files.writeString(commentFile, comment, StandardCharsets.UTF_8);
            if (comparison != null) {
                comparisonFile = reports.resolve("comparison-" + current.mode() + ".md");
                Files.writeString(comparisonFile, comparison.toMarkdown(), StandardCharsets.UTF_8);
            }
            String summaryFile = env.get("GITHUB_STEP_SUMMARY");
            if (summaryFile != null && !summaryFile.isBlank()) {
                Files.writeString(Path.of(summaryFile), comment.substring(MARKER.length() + 1) + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                summary = true;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Published(commentFile, comparisonFile, summary, recorded);
    }
}
