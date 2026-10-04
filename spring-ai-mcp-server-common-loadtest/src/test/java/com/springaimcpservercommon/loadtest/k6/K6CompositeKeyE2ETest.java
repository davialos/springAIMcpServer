package com.springaimcpservercommon.loadtest.k6;

import com.springaimcpservercommon.loadtest.api.LoadTestGenerator;
import com.springaimcpservercommon.loadtest.api.LoadTestRunner;
import com.springaimcpservercommon.loadtest.data.FieldPlan;
import com.springaimcpservercommon.loadtest.discovery.Documents;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * A composite foreign key ({@code shipment_items(order_id, line_no) → order_lines}): both request fields must come
 * from the same parent row. The plan binds them to one tuple pool; every request k6 sends carries a pair that
 * exists, never order 1 with the line of order 2.
 */
class K6CompositeKeyE2ETest {

    @TempDir
    Path dir;

    private Path project() throws IOException {
        Path p = dir.resolve("shop");
        Path migration = Files.createDirectories(p.resolve("src/main/resources/db/migration"));
        Files.writeString(migration.resolve("V1__init.sql"), """
                create table orders (id bigint primary key);
                create table order_lines (order_id bigint references orders, line_no int, sku varchar(20),
                                          primary key (order_id, line_no));
                create table shipment_items (id bigint primary key, order_id bigint not null, line_no int not null,
                                             qty int not null,
                                             foreign key (order_id, line_no) references order_lines (order_id, line_no));
                """);
        Path src = Files.createDirectories(p.resolve("src/main/java/shop"));
        Files.writeString(src.resolve("ShipmentController.java"), """
                package shop;
                import jakarta.validation.constraints.NotNull;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/shipment-items")
                class ShipmentController {
                    @PostMapping Object add(@RequestBody NewShipmentItem item) { return null; }
                }
                record NewShipmentItem(@NotNull Long orderId, @NotNull Integer lineNo, @NotNull Integer qty) { }
                """);
        return p;
    }

    @Test
    void bothColumnsOfTheKeyComeFromOneParentRow() throws Exception {
        Path suite = dir.resolve("suite");
        LoadTestGenerator g = LoadTestGenerator.builder().project(project()).outDir(suite).noDatabase().build();
        var plan = g.discover().plan();
        FieldPlan order = plan.field("NewShipmentItem.orderId");
        FieldPlan line = plan.field("NewShipmentItem.lineNo");
        assertThat(order.pool().key()).isEqualTo("order_lines.order_id,line_no").isEqualTo(line.pool().key());
        assertThat(order.component()).isZero();
        assertThat(line.component()).isEqualTo(1);
        assertThat(plan.field("NewShipmentItem.qty").pool()).isNull();
        g.generate();
        String providers = Files.readString(suite.resolve("providers/schemas.js"));
        assertThat(providers).contains("\"component\":1");

        // the sampled tuples (what DatabaseSampler returns for a composite pool)
        Files.writeString(suite.resolve("data/real.json"), """
                {"order_lines.order_id,line_no": [[1, 1], [1, 2], [2, 1], [3, 7]]}
                """);
        assumeThat(LoadTestRunner.findK6(null)).as("k6 binary (K6_BIN or PATH)").isPresent();
        Set<String> valid = Set.of("1/1", "1/2", "2/1", "3/7");
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            JsonNode b = Documents.parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String pair = b.path("orderId").asLong() + "/" + b.path("lineNo").asLong();
            seen.add(pair);
            int status = valid.contains(pair) ? 201 : 409; // the database's composite foreign key
            ex.sendResponseHeaders(status, -1);
            ex.close();
        });
        server.start();
        try {
            LoadTestRunner.RunResult r = LoadTestRunner.suite(suite).mode("smoke").dataMode("real")
                    .env("BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort())
                    .env("SEED", "false").env("ITERATIONS", "10").output(l -> { }).run();
            assertThat(seen).isNotEmpty().allMatch(valid::contains);
            assertThat(r.passed()).as(r.output()).isTrue();
        } finally {
            server.stop(0);
        }
    }
}
