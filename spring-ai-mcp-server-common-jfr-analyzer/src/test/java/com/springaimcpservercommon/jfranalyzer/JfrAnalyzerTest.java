package com.springaimcpservercommon.jfranalyzer;

import com.springaimcpservercommon.jfranalyzer.model.AnalysisReport;
import com.springaimcpservercommon.jfranalyzer.model.Finding;
import com.springaimcpservercommon.jfranalyzer.model.GcGroup;
import com.springaimcpservercommon.jfranalyzer.model.Hotspot;
import com.springaimcpservercommon.jfranalyzer.model.HotspotReport;
import com.springaimcpservercommon.jfranalyzer.model.StackFrameView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static com.springaimcpservercommon.jfranalyzer.TestRecordings.SAMPLE_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/** Records a known workload and checks the analyzer points at the methods that caused each cost. */
class JfrAnalyzerTest {

    private static final String SAMPLE = SAMPLE_PACKAGE + ".SampleWorkload$";
    private static AnalysisReport report;

    @BeforeAll
    static void analyze() throws IOException {
        report = new JfrAnalyzer(AnalyzerOptions.defaults(TestRecordings.sample(), List.of(SAMPLE_PACKAGE)))
                .analyze();
    }

    @Test
    void cpuHotSpotIsThePrimeLoopWithItsLine() {
        HotspotReport cpu = report.cpu().execution();
        assertThat(cpu.events()).isGreaterThan(50);
        Hotspot top = cpu.hotspots().getFirst();
        assertThat(top.className()).isEqualTo(SAMPLE + "CpuBurner");
        assertThat(top.methodName()).isIn("isPrime", "burn");
        assertThat(top.method()).contains("CpuBurner.");
        assertThat(top.location()).matches(".*CpuBurner\\.\\w+\\(SampleWorkload\\.java:\\d+\\)");
        assertThat(top.lines()).isNotEmpty();
        assertThat(top.stacks()).isNotEmpty();
        assertThat(top.stacks().getFirst().frames()).anyMatch(StackFrameView::inPackage);
        assertThat(cpu.hotLines().getFirst().line()).isPositive();
        assertThat(report.summary().topCpuLocation()).contains("SampleWorkload.java:");
    }

    @Test
    void allocationHotSpotIsTheAllocatorWithItsType() {
        HotspotReport alloc = report.memory().allocations();
        assertThat(alloc.present()).isTrue();
        Hotspot top = alloc.hotspots().getFirst();
        assertThat(top.className()).isEqualTo(SAMPLE + "Allocator");
        assertThat(top.details()).extracting(n -> n.name()).contains("byte[]");
        assertThat(report.memory().allocatedBytes()).isPositive();
        assertThat(report.memory().allocationRateBytesPerSec()).isPositive();
    }

    @Test
    void lockContentionPointsAtTheSynchronizedBlockAndTheLock() {
        Hotspot monitor = report.threads().monitorEnter().hotspots().getFirst();
        assertThat(monitor.className()).isEqualTo(SAMPLE + "Contender");
        assertThat(monitor.methodName()).isEqualTo("enterMonitor");
        assertThat(monitor.details().getFirst().name()).isEqualTo("java.lang.Object");
        assertThat(report.threads().lockOwners()).isNotEmpty();

        assertThat(report.threads().park().hotspots())
                .anyMatch(h -> h.methodName().equals("takeLock") && h.className().equals(SAMPLE + "Contender"));
        assertThat(report.summary().monitorBlockedMs()).isPositive();
    }

    @Test
    void exceptionThrowSiteIsTheThrowingMethodNotTheConstructor() {
        HotspotReport sites = report.exceptions().throwSites();
        Hotspot top = sites.hotspots().getFirst();
        assertThat(top.className()).isEqualTo(SAMPLE + "Thrower");
        assertThat(top.methodName()).isEqualTo("fail");
        assertThat(top.details().getFirst().name()).isEqualTo(SAMPLE + "SampleException");
        assertThat(report.exceptions().throwablesCreated()).isPositive();
    }

    @Test
    void gcAndHeapAreReported() {
        assertThat(report.gc().count()).isPositive();
        assertThat(report.gc().byCause()).extracting(GcGroup::name).anyMatch(c -> c.contains("System.gc"));
        assertThat(report.gc().longestPauses()).isNotEmpty();
        assertThat(report.memory().heapMaxBytes()).isPositive();
        assertThat(report.memory().heapTimeline()).isNotEmpty();
        assertThat(report.memory().liveSetLastBytes()).isNotNull();
        assertThat(report.findings()).extracting(Finding::title).anyMatch(t -> t.contains("System.gc()"));
    }

    @Test
    void metaAndFindingsAreFilledIn() {
        assertThat(report.meta().eventCounts()).containsKey("jdk.ExecutionSample");
        assertThat(report.meta().durationMillis()).isGreaterThan(1_000);
        assertThat(report.meta().jvm().jvmVersion()).isNotBlank();
        assertThat(report.cpu().jvmCpuTimeline()).isNotEmpty();
        assertThat(report.findings()).isSortedAccordingTo((a, b) -> a.severity().compareTo(b.severity()));
    }

    @Test
    void withoutPackagesEveryFrameCounts() throws IOException {
        AnalysisReport all = new JfrAnalyzer(AnalyzerOptions.defaults(TestRecordings.sample(), List.of())).analyze();
        assertThat(all.cpu().execution().attributedPercent()).isEqualTo(100.0);
        assertThat(all.findings()).extracting(Finding::title).anyMatch(t -> t.startsWith("No packages given"));
    }

    @Test
    void unknownPackageIsReported() throws IOException {
        AnalysisReport none = new JfrAnalyzer(AnalyzerOptions.defaults(TestRecordings.sample(),
                List.of("org.nowhere"))).analyze();
        assertThat(none.cpu().execution().hotspots()).isEmpty();
        assertThat(none.findings()).extracting(Finding::title)
                .contains("No stack frame matched the requested packages");
    }
}
