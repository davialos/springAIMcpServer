package com.springaimcpservercommon.loadtest.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The trend history and the pull-request comment a CI job publishes. */
public class CiReportTest {

    @TempDir
    Path dir;

    /** Writes a report file as the suite's handleSummary does. */
    public static Path report(Path suite, String stamp, double p95, double failed, boolean thresholdsFailed) throws IOException {
        Path reports = Files.createDirectories(suite.resolve("reports"));
        Path file = reports.resolve("smoke-" + stamp + ".json");
        Files.writeString(file, """
                {"mode":"smoke","dataMode":"auto","baseUrl":"http://localhost:8080",
                 "apis":[{"api":"getOrder","name":"GET /orders/{id}","requests":200,"rps":10,"failed":%s,"avg":10,"p95":%s,"p99":90,"max":120},
                         {"api":"listOrders","name":"GET /orders","requests":100,"rps":5,"failed":0,"avg":8,"p95":30,"p99":40,"max":50}],
                 "failedThresholds":%s,
                 "metrics":{"http_reqs":{"values":{"count":300,"rate":15}},"http_req_failed":{"values":{"rate":%s}},
                            "http_req_duration":{"values":{"p(95)":%s}}}}
                """.formatted(failed, p95, thresholdsFailed ? "[\"http_req_duration: p(95)<800\"]" : "[]", failed, p95));
        return file;
    }

    @Test
    void runsAreAppendedOnceAndReadBackOldestFirst() throws IOException {
        Path history = dir.resolve("history/smoke.jsonl");
        LoadTestReport a = LoadTestReport.read(report(dir, "2026-10-01T10-00-00-000Z", 100, 0, false));
        LoadTestReport b = LoadTestReport.read(report(dir, "2026-10-02T10-00-00-000Z", 110, 0.01, true));
        assertThat(TrendHistory.record(history, a, "aaaaaaaa11", Instant.parse("2026-10-01T10:00:00Z"))).isTrue();
        assertThat(TrendHistory.record(history, a, "aaaaaaaa11", Instant.parse("2026-10-01T10:05:00Z")))
                .as("a report file is recorded once").isFalse();
        assertThat(TrendHistory.record(history, b, "bbbbbbbb22", Instant.parse("2026-10-02T10:00:00Z"))).isTrue();
        List<TrendHistory.Point> points = TrendHistory.read(history, "smoke", 10);
        assertThat(points).extracting(TrendHistory.Point::commit).containsExactly("aaaaaaaa11", "bbbbbbbb22");
        assertThat(points.get(1).passed()).isFalse();
        assertThat(points.get(0).apiP95()).containsEntry("getOrder", 100.0).containsEntry("listOrders", 30.0);
        assertThat(TrendHistory.read(history, "smoke", 1)).hasSize(1);
        assertThat(TrendHistory.read(history, "load", 10)).isEmpty();
        Files.writeString(history, "{torn\n", java.nio.file.StandardOpenOption.APPEND);
        assertThat(TrendHistory.read(history, null, 10)).as("a torn line does not lose the history").hasSize(2);
    }

    @Test
    void aSlowDriftIsFlaggedAlthoughNoSingleRunRegressed() throws IOException {
        Path history = dir.resolve("h.jsonl");
        double[] p95 = {100, 102, 101, 103, 100, 104, 160};
        for (int i = 0; i < p95.length; i++) {
            LoadTestReport r = LoadTestReport.read(report(dir, "2026-10-0" + (i + 1) + "T10-00-00-000Z", p95[i], 0, false));
            TrendHistory.record(history, r, "c" + i, Instant.parse("2026-10-0" + (i + 1) + "T10:00:00Z"));
        }
        List<TrendHistory.Point> points = TrendHistory.read(history, "smoke", 20);
        assertThat(TrendHistory.p95Drift(points)).isGreaterThan(50);
        assertThat(TrendHistory.toMarkdown(points)).contains("### Trend (last 7 runs)").contains("above** the median")
                .contains("`c6`").contains("▁").contains("█");
        assertThat(TrendHistory.p95Drift(points.subList(0, 3))).as("too little history to judge").isNull();
    }

    @Test
    void sparklinesScaleToTheSeriesAndShowGaps() {
        assertThat(TrendHistory.sparkline(new ArrayList<>(java.util.Arrays.asList(1.0, 2.0, null, 8.0)))).isEqualTo("▁▂·█");
        assertThat(TrendHistory.sparkline(List.of(5.0, 5.0))).as("a flat series").hasSize(2);
    }

    @Test
    void commitComesFromWhicheverCiSystemRuns() {
        assertThat(TrendHistory.commitFrom(Map.of("CI_COMMIT_SHA", "abc"))).isEqualTo("abc");
        assertThat(TrendHistory.commitFrom(Map.of("GITHUB_SHA", "g", "GIT_COMMIT", "j"))).isEqualTo("g");
        assertThat(TrendHistory.commitFrom(Map.of())).isEmpty();
    }

    @Test
    void theCommentCarriesTheVerdictTheComparisonAndTheTrend() throws IOException {
        LoadTestReport base = LoadTestReport.read(report(dir, "2026-10-01T10-00-00-000Z", 100, 0, false));
        LoadTestReport slower = LoadTestReport.read(report(dir, "2026-10-02T10-00-00-000Z", 400, 0, false));
        ReportComparison regression = ReportComparison.compare(base, slower, ReportComparison.Rules.DEFAULTS);
        String comment = CiReport.comment(slower, regression, List.of());
        assertThat(comment).startsWith(CiReport.MARKER + "\n## ❌ Load test — smoke").contains("### Load test comparison")
                .contains("**regression**").contains("p95 100.0 → 400.0 ms (+300%)");
        String first = CiReport.comment(base, null, List.of());
        assertThat(first).contains("## ✅ Load test — smoke").contains("No baseline yet");
        String thresholds = CiReport.comment(LoadTestReport.read(report(dir, "2026-10-03T10-00-00-000Z", 900, 0, true)), null, List.of());
        assertThat(thresholds).contains("## ❌").contains("**Thresholds failed:**").contains("http_req_duration: p(95)<800");
    }

    @Test
    void publishWritesTheFilesAndAppendsTheJobSummary() throws IOException {
        LoadTestReport base = LoadTestReport.read(report(dir, "2026-10-01T10-00-00-000Z", 100, 0, false));
        LoadTestReport now = LoadTestReport.read(report(dir, "2026-10-02T10-00-00-000Z", 105, 0, false));
        Path summary = dir.resolve("step-summary.md");
        Path history = dir.resolve("history/smoke.jsonl");
        CiReport.Published p = CiReport.publish(dir, now, ReportComparison.compare(base, now, ReportComparison.Rules.DEFAULTS),
                history, Map.of("GITHUB_STEP_SUMMARY", summary.toString(), "GITHUB_SHA", "deadbeefcafe"));
        assertThat(p.recorded()).isTrue();
        assertThat(p.summary()).isTrue();
        assertThat(p.comment()).isEqualTo(dir.resolve("reports/pr-comment.md")).content().startsWith(CiReport.MARKER);
        assertThat(p.comparison()).isEqualTo(dir.resolve("reports/comparison-smoke.md")).content().contains("No regression.");
        assertThat(summary).content().doesNotContain(CiReport.MARKER).contains("Load test — smoke").contains("`deadbeef`");
        CiReport.Published again = CiReport.publish(dir, now, null, history, Map.of());
        assertThat(again.recorded()).isFalse();
        assertThat(again.summary()).isFalse();
        assertThat(again.comparison()).isNull();
    }
}
