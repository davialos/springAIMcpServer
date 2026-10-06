package com.springaimcpservercommon.loadtest.observe;

import com.springaimcpservercommon.loadtest.observe.ServerChecks.Finding;
import com.springaimcpservercommon.loadtest.observe.ServerChecks.Level;
import com.springaimcpservercommon.loadtest.observe.ServerChecks.Snapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ServerChecksTest {

    private static final Instant T0 = Instant.parse("2026-10-10T13:00:00Z");

    private static Snapshot snap(int second, String text) {
        return new Snapshot(T0.plusSeconds(second), PrometheusText.parse(text));
    }

    private static Finding finding(List<Finding> all, String id) {
        return all.stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow(() -> new AssertionError(id + " in " + all));
    }

    @Test
    void parsesPrometheusTextWithBracesInLabelsAndSpecialValues() {
        List<PrometheusText.Sample> s = PrometheusText.parse("""
                # TYPE x counter
                http_server_requests_seconds_count{method="GET",uri="/a/{id}",status="200"} 12.0
                jvm_memory_max_bytes{area="nonheap",id="Metaspace"} -1.0
                some_metric_bucket{le="+Inf"} 5 1700000000000
                broken line
                """);
        assertThat(s).hasSize(3);
        assertThat(s.getFirst().labels()).containsEntry("uri", "/a/{id}");
        assertThat(s.get(2).labels()).containsEntry("le", "+Inf");
        assertThat(s.get(2).value()).isEqualTo(5.0);
    }

    @Test
    void aHealthyServerPassesEveryCheckItHasMetricsFor() {
        String a = """
                hikaricp_connections_pending{pool="main"} 0
                hikaricp_connections_active{pool="main"} 3
                hikaricp_connections_max{pool="main"} 10
                jvm_gc_pause_seconds_sum{action="end of minor GC"} 1.0
                http_server_requests_seconds_count{status="200"} 100
                logback_events_total{level="error"} 2
                jvm_memory_used_bytes{area="heap",id="G1 Eden"} 100
                jvm_memory_max_bytes{area="heap",id="G1 Eden"} 1000
                jvm_memory_max_bytes{area="nonheap",id="Metaspace"} -1
                process_cpu_usage 0.3
                jvm_threads_live_threads 40
                """;
        String b = a.replace("jvm_gc_pause_seconds_sum{action=\"end of minor GC\"} 1.0", "jvm_gc_pause_seconds_sum{action=\"end of minor GC\"} 2.0")
                .replace("status=\"200\"} 100", "status=\"200\"} 5000");
        List<Finding> f = ServerChecks.evaluate(List.of(snap(0, a), snap(60, b)), ServerChecks.Settings.defaults());
        assertThat(ServerChecks.failed(f)).isFalse();
        assertThat(f).extracting(Finding::id).contains("hikari-pending", "gc-share", "server-5xx", "log-errors",
                "heap-usage", "cpu", "threads-growth");
        assertThat(finding(f, "gc-share").message()).contains("2%");
        assertThat(f).extracting(Finding::id).as("no Tomcat metrics: no Tomcat check").doesNotContain("tomcat-threads");
    }

    @Test
    void poolWaitGcFiveHundredsAndLogErrorsFailTheRun() {
        String a = """
                hikaricp_connections_pending{pool="main"} 4
                jvm_gc_pause_seconds_sum{action="x"} 0
                http_server_requests_seconds_count{status="200"} 100
                http_server_requests_seconds_count{status="503"} 0
                logback_events_total{level="error"} 0
                tomcat_threads_busy_threads 200
                tomcat_threads_config_max_threads 200
                """;
        String b = """
                hikaricp_connections_pending{pool="main"} 9
                jvm_gc_pause_seconds_sum{action="x"} 12
                http_server_requests_seconds_count{status="200"} 900
                http_server_requests_seconds_count{status="503"} 7
                logback_events_total{level="error"} 3
                tomcat_threads_busy_threads 200
                tomcat_threads_config_max_threads 200
                """;
        List<Finding> f = ServerChecks.evaluate(List.of(snap(0, a), snap(30, a), snap(60, b)), ServerChecks.Settings.defaults());
        assertThat(finding(f, "hikari-pending").level()).isEqualTo(Level.FAIL);
        assertThat(finding(f, "gc-share").level()).as("12 s of 60 s").isEqualTo(Level.FAIL);
        assertThat(finding(f, "server-5xx").message()).startsWith("7 requests");
        assertThat(finding(f, "server-5xx").level()).isEqualTo(Level.FAIL);
        assertThat(finding(f, "log-errors").level()).isEqualTo(Level.FAIL);
        assertThat(finding(f, "tomcat-threads").level()).as("saturated threads only warn").isEqualTo(Level.WARN);
        assertThat(ServerChecks.failed(f)).isTrue();
    }

    @Test
    void limitsAreConfigurable() {
        String a = "http_server_requests_seconds_count{status=\"500\"} 0\nlogback_events_total{level=\"error\"} 0\n";
        String b = "http_server_requests_seconds_count{status=\"500\"} 3\nlogback_events_total{level=\"error\"} 5\n";
        var lenient = new ServerChecks.Settings(0.2, 0.95, 0.05, 0.95, 0.9, 0.9, false, false);
        List<Finding> f = ServerChecks.evaluate(List.of(snap(0, a), snap(10, b)), lenient);
        assertThat(ServerChecks.failed(f)).isFalse();
        assertThat(finding(f, "server-5xx").level()).isEqualTo(Level.WARN);
        assertThat(ServerChecks.evaluate(List.of(snap(0, a)), lenient)).as("one scrape is not enough").isEmpty();
    }
}
