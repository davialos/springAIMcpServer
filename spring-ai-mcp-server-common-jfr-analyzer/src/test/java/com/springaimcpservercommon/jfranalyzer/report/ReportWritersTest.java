package com.springaimcpservercommon.jfranalyzer.report;

import com.springaimcpservercommon.jfranalyzer.AnalyzerOptions;
import com.springaimcpservercommon.jfranalyzer.JfrAnalyzer;
import com.springaimcpservercommon.jfranalyzer.TestRecordings;
import com.springaimcpservercommon.jfranalyzer.model.AnalysisReport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportWritersTest {

    private static AnalysisReport report;

    @BeforeAll
    static void analyze() throws IOException {
        report = new JfrAnalyzer(AnalyzerOptions.defaults(TestRecordings.sample(),
                List.of(TestRecordings.SAMPLE_PACKAGE)),
                Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC)).analyze();
    }

    @Test
    void jsonIsValidAndCarriesTheHotSpots() {
        String json = JsonReportWriter.write(report, true);
        JsonNode root = JsonMapper.builder().build().readTree(json);
        assertThat(root.path("meta").path("analyzedAt").asString()).isEqualTo("2026-10-01T00:00:00Z");
        JsonNode top = root.path("cpu").path("execution").path("hotspots").get(0);
        assertThat(top.path("location").asString()).contains("SampleWorkload.java:");
        assertThat(top.path("rank").asInt()).isEqualTo(1);
        assertThat(root.path("findings").isArray()).isTrue();
        assertThat(root.path("summary").path("cpuSamples").asLong()).isPositive();
    }

    @Test
    void prettyAndCompactJsonHaveTheSameTokens() {
        String compact = JsonReportWriter.write(report, false);
        String pretty = JsonReportWriter.write(report, true);
        JsonMapper mapper = JsonMapper.builder().build();
        assertThat(mapper.readTree(pretty)).isEqualTo(mapper.readTree(compact));
        assertThat(compact).doesNotContain("\n");
    }

    @Test
    void indentLeavesStringsAlone() {
        String compact = "{\"a\":[1,{\"b\":\"x,{y}:[z]\\\"q\"}],\"c\":{},\"d\":[]}";
        String pretty = JsonReportWriter.indent(compact);
        assertThat(pretty).contains("\"x,{y}:[z]\\\"q\"").contains("\"c\": {}").contains("\"d\": []");
    }

    @Test
    void treeConvertsRecordsAndDropsNonFiniteNumbers() {
        record R(String s, double d, double nan) {
        }
        Object tree = JsonReportWriter.tree(new R("x", 1.23456, Double.NaN));
        assertThat(tree).isInstanceOf(Map.class);
        Map<?, ?> map = (Map<?, ?>) tree;
        assertThat(map.get("s")).isEqualTo("x");
        assertThat(map.get("d")).isEqualTo(1.235);
        assertThat(map.containsKey("nan")).isTrue();
        assertThat(map.get("nan")).isNull();
    }

    @Test
    void htmlIsSelfContainedAndEscaped() {
        String html = HtmlReportWriter.write(report);
        assertThat(html).startsWith("<!doctype html>");
        assertThat(html).contains("<title>JFR Analysis Report</title>");
        assertThat(html).contains("SampleWorkload.java:");
        assertThat(html).doesNotContain("<script src").doesNotContain("<link ");
        assertThat(html).contains("&lt;init&gt;").doesNotContain("(<init>");
        int data = html.indexOf("id=\"chart-data\">");
        String chartData = html.substring(data, html.indexOf("</script>", data));
        assertThat(chartData).doesNotContain("</");
        assertThat(html).contains("data-chart=\"heap\"").contains("data-chart=\"cpuLoad\"");
        assertThat(html).contains("id=\"findings\"").contains("id=\"gc\"").contains("id=\"threads\"");
    }

    @Test
    void escapeCoversHtmlSpecials() {
        assertThat(HtmlReportWriter.esc("<a href=\"x\">'&'</a>"))
                .isEqualTo("&lt;a href=&quot;x&quot;&gt;&#39;&amp;&#39;&lt;/a&gt;");
    }
}
