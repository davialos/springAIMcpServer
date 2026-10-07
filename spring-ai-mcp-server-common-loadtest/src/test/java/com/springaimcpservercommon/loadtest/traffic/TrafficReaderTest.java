package com.springaimcpservercommon.loadtest.traffic;

import com.springaimcpservercommon.loadtest.discovery.Documents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Production traffic read from Prometheus metrics and access logs, and applied to a suite. */
class TrafficReaderTest {

    private static final List<Route> ROUTES = List.of(new Route("getItem", "GET", "/items/{id}"),
            new Route("listItems", "GET", "/items"), new Route("createItem", "POST", "/items"),
            new Route("getPrice", "GET", "/items/{id}/price"), new Route("report", "GET", "/admin/report"));

    @Test
    void routesMatchMostSpecificTemplateFirstAndIgnoreVariableNames() {
        Route.Index index = new Route.Index(ROUTES);
        assertThat(index.match("GET", "/items/42").orElseThrow().id()).isEqualTo("getItem");
        assertThat(index.match("get", "/items/42/price").orElseThrow().id()).isEqualTo("getPrice");
        assertThat(index.match("GET", "/items/").orElseThrow().id()).isEqualTo("listItems");
        assertThat(index.match("DELETE", "/items/42")).isEmpty();
        assertThat(index.matchTemplate("GET", "/items/{itemId}").orElseThrow().id()).isEqualTo("getItem");
    }

    @Test
    void prometheusTextGivesTheMixErrorRatesAndLatency() {
        String text = """
                # HELP http_server_requests_seconds
                http_server_requests_seconds_count{method="GET",outcome="SUCCESS",status="200",uri="/items/{itemId}"} 700.0
                http_server_requests_seconds_sum{method="GET",outcome="SUCCESS",status="200",uri="/items/{itemId}"} 35.0
                http_server_requests_seconds_count{method="GET",outcome="CLIENT_ERROR",status="404",uri="/items/{itemId}"} 100.0
                http_server_requests_seconds_sum{method="GET",outcome="CLIENT_ERROR",status="404",uri="/items/{itemId}"} 5.0
                http_server_requests_seconds_count{method="POST",outcome="SUCCESS",status="201",uri="/items"} 150.0
                http_server_requests_seconds_count{method="POST",outcome="SERVER_ERROR",status="500",uri="/items"} 50.0
                http_server_requests_seconds_count{method="GET",outcome="CLIENT_ERROR",status="404",uri="NOT_FOUND"} 40.0
                http_server_requests_seconds_count{method="GET",outcome="SUCCESS",status="200",uri="/actuator/health"} 60.0
                http_server_requests_seconds_bucket{method="GET",status="200",uri="/items/{itemId}",le="0.05"} 400.0
                http_server_requests_seconds_bucket{method="GET",status="200",uri="/items/{itemId}",le="0.1"} 600.0
                http_server_requests_seconds_bucket{method="GET",status="200",uri="/items/{itemId}",le="0.5"} 700.0
                http_server_requests_seconds_bucket{method="GET",status="200",uri="/items/{itemId}",le="+Inf"} 700.0
                """;
        TrafficModel m = TrafficReader.fromPrometheus(text, ROUTES, 1000);
        assertThat(m.totalRequests()).isEqualTo(1000);
        assertThat(m.unmatched()).as("NOT_FOUND and the actuator").isEqualTo(100);
        assertThat(m.averageRate()).isEqualTo(1.0);
        TrafficModel.ApiTraffic get = m.apis().get("getItem");
        assertThat(get.share()).isEqualTo(0.8);
        assertThat(get.clientErrorRate()).isCloseTo(0.125, within(1e-9));
        assertThat(get.meanMs()).isCloseTo(50.0, within(1e-9));
        assertThat(get.p95Ms()).as("interpolated inside the 0.1..0.5 bucket").isBetween(100.0, 500.0);
        assertThat(m.apis().get("createItem").serverErrorRate()).isEqualTo(0.25);
        assertThat(m.apis()).doesNotContainKey("report");
    }

    @Test
    void prometheusQueryJsonIsReadToo() {
        String json = """
                {"status":"success","data":{"resultType":"vector","result":[
                  {"metric":{"method":"GET","uri":"/items","status":"200"},"value":[1700000000,"300"]},
                  {"metric":{"method":"POST","uri":"/items","status":"201"},"value":[1700000000,"100"]}]}}
                """;
        TrafficModel m = TrafficReader.fromPrometheus(json, ROUTES, 0);
        assertThat(m.apis().get("listItems").share()).isEqualTo(0.75);
        assertThat(m.averageRate()).as("no period: no rate").isZero();
    }

    private static List<String> clfLog() {
        List<String> lines = new ArrayList<>();
        // 10 users, each a session of get, post, get over a few minutes; one request per second in a busy minute
        for (int u = 0; u < 10; u++) {
            int base = u * 30;
            lines.add(clf("10.0.0." + u, "alice" + u, base, "GET", "/shop/items/" + (100 + u), 200, "0.020"));
            lines.add(clf("10.0.0." + u, "alice" + u, base + 5, "POST", "/shop/items", 201, "0.080"));
            lines.add(clf("10.0.0." + u, "alice" + u, base + 9, "GET", "/shop/items/" + (200 + u) + "?x=1", 200, "0.030"));
        }
        lines.add(clf("10.9.9.9", "-", 100, "GET", "/shop/static/app.js", 200, "0.001")); // not an API
        lines.add("garbage line");
        return lines;
    }

    private static String clf(String ip, String user, int second, String method, String path, int status, String time) {
        return String.format("%s - %s [10/Oct/2026:13:%02d:%02d +0000] \"%s %s HTTP/1.1\" %d 512 \"-\" \"k6\" %s",
                ip, user, second / 60, second % 60, method, path, status, time);
    }

    @Test
    void accessLogsGiveMixRateLatencyAndSessions() {
        TrafficModel m = TrafficReader.fromAccessLog(clfLog(), ROUTES, "/shop", "auto");
        assertThat(m.totalRequests()).isEqualTo(30);
        assertThat(m.unmatched()).isEqualTo(1);
        assertThat(m.apis().get("getItem").share()).isCloseTo(20 / 30.0, within(1e-9));
        assertThat(m.apis().get("createItem").p95Ms()).as("0.080 s as ms").isEqualTo(80.0);
        assertThat(m.averageRate()).as("30 requests over ~4.5 minutes").isBetween(0.05, 0.2);
        assertThat(m.sessions()).isEqualTo(10);
        assertThat(m.entry()).containsOnlyKeys("getItem");
        assertThat(m.transitions().get("getItem")).containsEntry("createItem", 0.5).containsEntry("$end", 0.5);
        assertThat(m.transitions().get("createItem")).containsEntry("getItem", 1.0);
    }

    @Test
    void jsonLinesAreReadWithTheUsualFieldNames() {
        List<String> lines = List.of(
                "{\"@timestamp\":\"2026-10-10T13:00:00Z\",\"method\":\"GET\",\"uri\":\"/items/7\",\"status\":200,\"request_time\":0.050,\"remote_addr\":\"1.1.1.1\"}",
                "{\"@timestamp\":\"2026-10-10T13:00:03Z\",\"method\":\"POST\",\"uri\":\"/items\",\"status\":\"201\",\"duration\":120,\"remote_addr\":\"1.1.1.1\"}");
        TrafficModel m = TrafficReader.fromAccessLog(lines, ROUTES, null, "auto");
        assertThat(m.totalRequests()).isEqualTo(2);
        assertThat(m.apis().get("getItem").meanMs()).as("a decimal is seconds").isEqualTo(50.0);
        assertThat(m.apis().get("createItem").meanMs()).as("an integer is milliseconds").isEqualTo(120.0);
        assertThat(m.averageRate()).isCloseTo(2 / 3.0, within(1e-9));
    }

    @Test
    void importingWritesWeightsTheProductionProfileAndTheTrafficFile(@TempDir Path suite) throws IOException {
        Files.createDirectories(suite.resolve("data"));
        Files.writeString(suite.resolve("loadtest.config.json"), """
                {"baseUrl":"http://localhost:8080/shop",
                 "apis":{"getItem":{"method":"GET","path":"/items/{id}","weight":6},
                         "createItem":{"method":"POST","path":"/items","weight":2},
                         "report":{"method":"GET","path":"/admin/report","weight":6}},
                 "modes":{"production":{"executor":"ramping-arrival-rate","baseRate":10,"stages":[]}}}
                """);
        assertThat(TrafficImporter.routes(suite)).extracting(Route::id).containsExactly("getItem", "createItem", "report");
        TrafficModel m = TrafficReader.fromAccessLog(clfLog(), ROUTES, "/shop", "auto");
        TrafficImporter.apply(suite, m, "test.log", new TrafficImporter.Options(true, 1.5, 4.0), new ArrayList<String>()::add);
        JsonNode c = Documents.parse(Files.readString(suite.resolve("loadtest.config.json")));
        assertThat(c.path("apis").path("getItem").path("weight").asInt()).isEqualTo(667);
        assertThat(c.path("apis").path("createItem").path("weight").asInt()).isEqualTo(333);
        assertThat(c.path("apis").path("report").path("weight").asInt()).as("never seen in production").isZero();
        assertThat(c.path("apis").path("createItem").path("p95Ms").asInt()).as("80 ms x 1.5, rounded up").isEqualTo(120);
        assertThat(c.path("modes").path("production").path("baseRate").asInt()).as("--rate wins").isEqualTo(4);
        assertThat(c.path("traffic").path("requests").asInt()).isEqualTo(30);
        JsonNode t = Documents.parse(Files.readString(suite.resolve("data/traffic.json")));
        assertThat(t.path("transitions").path("getItem").path("createItem").asDouble()).isEqualTo(0.5);
        assertThat(t.path("sessions").path("count").asInt()).isEqualTo(10);
    }
}
