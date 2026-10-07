package com.springaimcpservercommon.loadtest.cli;

import com.springaimcpservercommon.loadtest.api.CiReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.springaimcpservercommon.loadtest.api.CiReportTest.report;
import static org.assertj.core.api.Assertions.assertThat;

/** {@code compare --ci}, {@code --update-baseline} and {@code trend}: what a pipeline calls after a run. */
class CiCommandsTest {

    @TempDir
    Path dir;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return new LoadTestCli(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), new ByteArrayInputStream(new byte[0])).execute(args);
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void theFirstRunHasNoBaselineAndBecomesIt() throws IOException {
        report(dir, "2026-10-01T10-00-00-000Z", 100, 0, false);
        Path baseline = dir.resolve("baseline/smoke.json");
        assertThat(run("compare", "--suite", dir.toString(), "--baseline", baseline.toString(), "--mode", "smoke", "--ci",
                "--update-baseline")).as(err.toString(StandardCharsets.UTF_8)).isZero();
        assertThat(out()).contains("No baseline at").contains("Baseline updated from smoke-2026-10-01");
        assertThat(baseline).exists();
        assertThat(dir.resolve("reports/pr-comment.md")).content().startsWith(CiReport.MARKER).contains("No baseline yet");
        assertThat(dir.resolve("history/trend.jsonl")).exists();
    }

    @Test
    void aRegressionFailsTheGateAndLeavesTheBaselineAlone() throws IOException {
        Path base = report(dir, "2026-10-01T10-00-00-000Z", 100, 0, false);
        Path baseline = Files.copy(base, dir.resolve("baseline.json"));
        report(dir, "2026-10-02T10-00-00-000Z", 400, 0, false);
        assertThat(run("compare", "--suite", dir.toString(), "--baseline", baseline.toString(), "--ci", "--update-baseline"))
                .isEqualTo(3);
        assertThat(out()).contains("**regression**");
        assertThat(dir.resolve("reports/pr-comment.md")).content().contains("## ❌");
        assertThat(baseline).as("a regressed run never becomes the baseline").hasSameTextualContentAs(base);
        out.reset();
        report(dir, "2026-10-03T10-00-00-000Z", 104, 0, false);
        assertThat(run("compare", "--suite", dir.toString(), "--baseline", baseline.toString(), "--update-baseline")).isZero();
        assertThat(out()).contains("Baseline updated");
    }

    @Test
    void trendRecordsAndPrintsTheHistoryAndGatesOnDrift() throws IOException {
        double[] p95 = {100, 101, 99, 102, 100, 150};
        for (int i = 0; i < p95.length; i++) {
            report(dir, "2026-10-0" + (i + 1) + "T10-00-00-000Z", p95[i], 0, false);
            assertThat(run("trend", "--suite", dir.toString(), "--mode", "smoke", "--record")).isZero();
        }
        assertThat(out()).contains("### Trend (last 6 runs)").contains("above** the median");
        out.reset();
        assertThat(run("trend", "--suite", dir.toString(), "--mode", "smoke", "--max-drift", "25")).isEqualTo(3);
        assertThat(run("trend", "--suite", dir.toString(), "--mode", "smoke", "--max-drift", "80")).isZero();
        out.reset();
        assertThat(run("trend", "--suite", dir.resolve("nowhere").toString())).isZero();
        assertThat(out()).contains("No runs recorded");
    }
}
