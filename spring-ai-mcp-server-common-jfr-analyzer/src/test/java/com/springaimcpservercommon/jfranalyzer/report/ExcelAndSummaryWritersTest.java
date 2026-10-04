package com.springaimcpservercommon.jfranalyzer.report;

import com.springaimcpservercommon.jfranalyzer.AnalyzerOptions;
import com.springaimcpservercommon.jfranalyzer.JfrAnalyzer;
import com.springaimcpservercommon.jfranalyzer.TestRecordings;
import com.springaimcpservercommon.jfranalyzer.model.AnalysisReport;
import com.springaimcpservercommon.jfranalyzer.report.xlsx.XlsxWorkbookTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExcelAndSummaryWritersTest {

    private static AnalysisReport report;

    @BeforeAll
    static void analyze() throws IOException {
        report = new JfrAnalyzer(AnalyzerOptions.defaults(TestRecordings.sample(),
                List.of(TestRecordings.SAMPLE_PACKAGE)),
                Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC)).analyze();
    }

    @Test
    void workbookHasDashboardChartsAndDetailSheets() throws IOException {
        Map<String, byte[]> parts = XlsxWorkbookTest.unzip(ExcelReportWriter.write(report));
        String workbook = text(parts, "xl/workbook.xml");
        for (String sheet : List.of("Dashboard", "Findings", "Key metrics", "Hot spots", "Hot lines", "GC", "Memory",
                "Threads", "Recording", "Chart data")) {
            assertThat(workbook).contains("name=\"" + sheet + "\"");
        }
        String dashboard = text(parts, "xl/worksheets/sheet1.xml");
        assertThat(dashboard).contains("JFR performance dashboard")
                .contains("Health score " + report.executiveSummary().healthScore() + " / 100")
                .contains("showGridLines=\"0\"")
                .contains("SampleWorkload$CpuBurner")
                .contains("<drawing r:id=\"rId1\"/>");
        // Six charts: heap, CPU load, GC pauses, allocation rate, top CPU, top allocation.
        assertThat(parts.keySet().stream().filter(p -> p.startsWith("xl/charts/chart"))).hasSize(6);
        assertThat(text(parts, "xl/charts/chart5.xml")).contains("<c:barChart>").contains("CpuBurner");
        String hotspots = text(parts, "xl/worksheets/sheet4.xml");
        assertThat(hotspots).contains("SampleWorkload.java:").contains("<autoFilter ref=\"A1:L")
                .contains("state=\"frozen\"").contains("type=\"dataBar\"");
        // Stacks stay out of the workbook.
        assertThat(parts.values().stream().map(b -> new String(b, StandardCharsets.UTF_8)))
                .noneMatch(s -> s.contains("java.lang.Thread.run("));
    }

    @Test
    void summaryIsShareable() {
        String json = SummaryJsonWriter.write(report, true);
        JsonNode root = JsonMapper.builder().build().readTree(json);
        assertThat(root.path("schemaVersion").asString()).isEqualTo(SummaryJsonWriter.SCHEMA_VERSION);
        assertThat(root.path("executiveSummary").path("status").asString()).isIn("RED", "AMBER", "GREEN");
        assertThat(root.path("executiveSummary").path("keyMetrics").size()).isGreaterThan(5);
        assertThat(root.path("topHotspots").path("cpu").path("hotspots").get(0).path("location").asString())
                .contains("SampleWorkload.java:");
        assertThat(root.path("recording").path("file").asString()).isEqualTo("sample.jfr");
        // Nothing that identifies hosts, people or the machine's layout.
        String recordingDir = TestRecordings.sample().toAbsolutePath().getParent().toString();
        assertThat(json).doesNotContain(recordingDir).doesNotContain("jvmArguments").doesNotContain("stacks")
                .doesNotContain("topThreads").doesNotContain("cpu-burner").doesNotContain("java.lang.Thread.run");
    }

    @Test
    void fullReportCarriesTheExecutiveSummaryAndSchema() {
        JsonNode root = JsonMapper.builder().build().readTree(JsonReportWriter.write(report, false));
        assertThat(root.path("meta").path("schemaVersion").asString()).isEqualTo("jfr-analyzer/report/2");
        assertThat(root.path("executiveSummary").path("headline").asString()).isNotBlank();
        assertThat(root.path("executiveSummary").path("recommendations").size()).isPositive();
    }

    @Test
    void htmlShowsTheStatusBanner() {
        String html = HtmlReportWriter.write(report);
        assertThat(html).contains("class=\"exec " + report.executiveSummary().status().name() + "\"")
                .contains("health score " + report.executiveSummary().healthScore());
    }

    private static String text(Map<String, byte[]> parts, String name) {
        return new String(parts.get(name), StandardCharsets.UTF_8);
    }
}
