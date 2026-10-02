package com.springaimcpservercommon.jfranalyzer.collect;

import com.springaimcpservercommon.jfranalyzer.AnalyzerOptions;
import com.springaimcpservercommon.jfranalyzer.JfrAnalyzer;
import com.springaimcpservercommon.jfranalyzer.TestRecordings;
import com.springaimcpservercommon.jfranalyzer.model.AnalysisReport;
import com.springaimcpservercommon.jfranalyzer.model.ExecutiveSummary;
import com.springaimcpservercommon.jfranalyzer.model.HealthStatus;
import com.springaimcpservercommon.jfranalyzer.model.KeyMetric;
import com.springaimcpservercommon.jfranalyzer.model.Severity;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutiveSummaryBuilderTest {

    @Test
    void scoreAndStatusFollowTheFindings() throws IOException {
        AnalysisReport report = new JfrAnalyzer(AnalyzerOptions.defaults(TestRecordings.sample(),
                List.of(TestRecordings.SAMPLE_PACKAGE))).analyze();
        ExecutiveSummary exec = report.executiveSummary();
        long critical = report.findings().stream().filter(f -> f.severity() == Severity.CRITICAL).count();
        long warning = report.findings().stream().filter(f -> f.severity() == Severity.WARNING).count();
        assertThat(exec.healthScore()).isEqualTo((int) Math.max(0, 100 - 25 * critical - 8 * warning));
        HealthStatus expected = critical > 0 || exec.healthScore() < 50 ? HealthStatus.RED
                : warning > 0 || exec.healthScore() < 80 ? HealthStatus.AMBER : HealthStatus.GREEN;
        assertThat(exec.status()).isEqualTo(expected);
        assertThat(exec.topIssues()).hasSize((int) Math.min(6, critical + warning));
        assertThat(exec.recommendations()).isNotEmpty();
        assertThat(exec.keyMetrics()).extracting(KeyMetric::id).contains("cpu.hottestMethodPercent",
                "gc.overheadPercent", "gc.maxPauseMs", "heap.liveSetPercentOfMax", "allocation.rateBytesPerSec",
                "threads.lockBlockedMs");
        // The workload's prime loop dominates CPU, so that metric is flagged.
        KeyMetric hottest = exec.keyMetrics().stream().filter(m -> m.id().equals("cpu.hottestMethodPercent"))
                .findFirst().orElseThrow();
        assertThat(hottest.status()).isIn(HealthStatus.AMBER, HealthStatus.RED);
    }

    @Test
    void namesAreShortenedForReaders() {
        assertThat(ExecutiveSummaryBuilder.withoutPackages(
                "com.acme.order.OrderService.place(int) waits on java.util.concurrent.locks.ReentrantLock"))
                .isEqualTo("OrderService.place(int) waits on ReentrantLock");
        assertThat(ExecutiveSummaryBuilder.withoutPackages("1 explicit System.gc() collections"))
                .isEqualTo("1 explicit System.gc() collections");
        assertThat(ExecutiveSummaryBuilder.shortLocation("com.acme.order.OrderService.place(OrderService.java:42)"))
                .isEqualTo("OrderService.place(OrderService.java:42)");
        assertThat(ExecutiveSummaryBuilder.shortLocation("Top.run(Top.java:1)")).isEqualTo("Top.run(Top.java:1)");
    }

    @Test
    void secretsInCommandLinesAreMasked() {
        assertThat(Redactor.commandLine("-Xmx1g -Dspring.datasource.password=s3cr3t -Dapp.name=demo "
                + "--api.token=abc -DDB_URL=jdbc:postgresql://bob:hunter2@db/x -Dmy.secretKey=\"a b\""))
                .isEqualTo("-Xmx1g -Dspring.datasource.password=**** -Dapp.name=demo --api.token=**** "
                        + "-DDB_URL=jdbc:postgresql://bob:****@db/x -Dmy.secretKey=****");
        assertThat(Redactor.commandLine(null)).isNull();
    }
}
