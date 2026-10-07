package com.springaimcpservercommon.loadtest.k6;

import com.springaimcpservercommon.loadtest.api.LoadTestGenerator;
import com.springaimcpservercommon.loadtest.api.LoadTestRunner;
import com.springaimcpservercommon.loadtest.discovery.Documents;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * WebSocket, STOMP and Server-Sent Events endpoints found in the code and exercised by {@code channels-<profile>}
 * against a hand-rolled server (JDK sockets: handshake, frames, STOMP frames, an endless event stream), plus the
 * Kafka producer script. Forms and GraphQL ride along in the same project.
 */
class K6ChannelsE2ETest {

    @TempDir
    static Path dir;
    private static Path suite;
    private static String pristine;
    private static ServerSocket server;
    private static volatile boolean running = true;
    private static volatile boolean silent;
    private static final List<String> RECEIVED = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicInteger SSE_CONNECTIONS = new AtomicInteger();
    private static final AtomicInteger FORMS = new AtomicInteger();
    private static final List<String> GRAPHQL = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void generateAndServe() throws IOException {
        Path project = dir.resolve("shop");
        Path src = Files.createDirectories(project.resolve("src/main/java/shop"));
        Path res = Files.createDirectories(project.resolve("src/main/resources/graphql"));
        Files.writeString(project.resolve("src/main/resources/application.properties"),
                "server.servlet.context-path=/shop\nspring.kafka.bootstrap-servers=broker:9092\n");
        Files.writeString(res.resolve("schema.graphqls"), """
                type Query {
                  product(id: ID!): Product
                  products(filter: ProductFilter, limit: Int): [Product!]!
                }
                type Mutation {
                  addProduct(input: NewProduct!): Product
                  deleteProduct(id: ID!): Boolean
                }
                type Product { id: ID! name: String! price: Float! category: Category tags: [String!] }
                type Category { id: ID! label: String! }
                input ProductFilter { text: String minPrice: Float }
                input NewProduct { name: String! price: Float! kind: Kind! }
                enum Kind { PHYSICAL DIGITAL }
                """);
        Files.writeString(src.resolve("Config.java"), """
                package shop;
                @Configuration @EnableWebSocket
                class WsConfig implements WebSocketConfigurer {
                    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
                        registry.addHandler(new EchoHandler(), "/ws/echo");
                    }
                }
                @Configuration @EnableWebSocketMessageBroker
                class StompConfig implements WebSocketMessageBrokerConfigurer {
                    public void registerStompEndpoints(StompEndpointRegistry r) { r.addEndpoint("/stomp"); }
                    public void configureMessageBroker(MessageBrokerRegistry c) {
                        c.setApplicationDestinationPrefixes("/app");
                        c.enableSimpleBroker("/topic");
                    }
                }
                """);
        Files.writeString(src.resolve("Controllers.java"), """
                package shop;
                import org.springframework.web.bind.annotation.*;
                @Controller
                class ChatController {
                    @MessageMapping("/chat") @SendTo("/topic/chat")
                    public ChatMessage chat(ChatMessage m) { return m; }
                }
                @RestController
                class PriceController {
                    @GetMapping(value = "/prices/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
                    SseEmitter stream() { return null; }
                    @GetMapping("/prices") List<String> prices() { return null; }
                    @PostMapping(value = "/subscribe", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                    void subscribe(@RequestParam String email, @RequestParam(defaultValue = "weekly") String plan) { }
                }
                record ChatMessage(String user, String text) { }
                @Component
                class OrderEvents {
                    @KafkaListener(topics = "orders")
                    void on(OrderEvent e) { }
                }
                record OrderEvent(String id, int quantity) { }
                """);
        suite = dir.resolve("suite");
        LoadTestGenerator.builder().project(project).outDir(suite).noDatabase().build().generate();
        pristine = Files.readString(suite.resolve("loadtest.config.json"));
        server = new ServerSocket(0);
        Thread acceptor = new Thread(K6ChannelsE2ETest::acceptLoop, "channels-server");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @AfterAll
    static void stop() throws IOException {
        running = false;
        if (server != null) {
            server.close();
        }
    }

    @BeforeEach
    void reset() throws IOException {
        ObjectNode c = (ObjectNode) Documents.parse(pristine);
        c.path("channels").forEach(ch -> ((ObjectNode) ch).put("hold", "1s")); // keep the runs short
        ((ObjectNode) c.path("thinkTime")).put("min", 0).put("max", 0);
        Files.writeString(suite.resolve("loadtest.config.json"), c.toString());
        silent = false;
        RECEIVED.clear();
        SSE_CONNECTIONS.set(0);
        FORMS.set(0);
        GRAPHQL.clear();
    }

    // ── a minimal HTTP + WebSocket + STOMP + SSE + GraphQL server ──────────────────────────────────────

    private static void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                Thread t = new Thread(() -> serve(socket));
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                return sb.toString().strip();
            }
            sb.append((char) c);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static void serve(Socket socket) {
        try (socket) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            String request = line(in);
            if (request == null) {
                return;
            }
            Map<String, String> headers = new HashMap<>();
            for (String h = line(in); h != null && !h.isEmpty(); h = line(in)) {
                int i = h.indexOf(':');
                headers.put(h.substring(0, i).toLowerCase(), h.substring(i + 1).strip());
            }
            String[] parts = request.split(" ");
            String method = parts[0];
            String path = parts[1];
            if ("websocket".equalsIgnoreCase(headers.get("upgrade"))) {
                webSocket(path, headers, in, out);
                return;
            }
            int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            String body = new String(in.readNBytes(length), StandardCharsets.UTF_8);
            if (path.equals("/shop/prices/stream")) {
                SSE_CONNECTIONS.incrementAndGet();
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                for (int i = 0; running; i++) { // endless: the client's read timeout ends it
                    byte[] event = ("id: " + i + "\ndata: {\"price\": " + i + "}\n\n").getBytes(StandardCharsets.UTF_8);
                    out.write((Integer.toHexString(event.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write(event);
                    out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(150);
                }
            } else if (path.equals("/shop/subscribe") && method.equals("POST")) {
                boolean form = headers.getOrDefault("content-type", "").startsWith("application/x-www-form-urlencoded")
                        && body.contains("email=");
                if (form) {
                    FORMS.incrementAndGet();
                } else {
                    RECEIVED.add("rejected form: " + headers.get("content-type") + " / " + body);
                }
                respond(out, form ? 200 : 415, "{}");
            } else if (path.equals("/shop/graphql") && method.equals("POST")) {
                JsonNode b = Documents.parse(body);
                GRAPHQL.add(b.path("operationName").asString() + " " + b.path("query").asString());
                // a valid operation answers data; an unknown field would answer errors
                boolean valid = b.path("query").asString().contains("{") && b.has("operationName");
                respond(out, 200, valid ? "{\"data\":{\"ok\":true}}" : "{\"errors\":[{\"message\":\"bad\"}]}");
            } else {
                respond(out, 404, "");
            }
        } catch (Exception e) {
            // the client went away: expected for streams and sockets
        }
    }

    private static void respond(OutputStream out, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + status + " X\r\nContent-Type: application/json\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private static void webSocket(String path, Map<String, String> headers, InputStream in, OutputStream out)
            throws Exception {
        String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(
                (headers.get("sec-websocket-key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                        .getBytes(StandardCharsets.UTF_8)));
        String protocol = headers.containsKey("sec-websocket-protocol") ? "" : "";
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n" + protocol + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        boolean stomp = path.endsWith("/stomp");
        while (true) {
            int b0 = in.read();
            if (b0 < 0 || (b0 & 0x0f) == 8) {
                return; // closed
            }
            int b1 = in.read();
            long len = b1 & 0x7f;
            if (len == 126) {
                len = (in.read() << 8) | in.read();
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) {
                    len = (len << 8) | in.read();
                }
            }
            byte[] mask = in.readNBytes(4);
            byte[] data = in.readNBytes((int) len);
            for (int i = 0; i < data.length; i++) {
                data[i] ^= mask[i % 4];
            }
            if ((b0 & 0x0f) == 9) { // ping
                continue;
            }
            String text = new String(data, StandardCharsets.UTF_8);
            RECEIVED.add((stomp ? "stomp " : "ws ") + text.replace("\0", "").replace("\n", "|"));
            if (!stomp) {
                if (!silent) {
                    send(out, text);
                }
            } else if (text.startsWith("CONNECT")) {
                send(out, "CONNECTED\nversion:1.2\n\n\0");
            } else if (text.startsWith("SEND")) {
                String payload = text.substring(text.indexOf("\n\n") + 2);
                send(out, "MESSAGE\ndestination:/topic/chat\nsubscription:sub-0\nmessage-id:1\n\n" + payload);
            }
        }
    }

    private static synchronized void send(OutputStream out, String text) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x81);
        if (data.length < 126) {
            frame.write(data.length);
        } else {
            frame.write(126);
            frame.write(data.length >> 8);
            frame.write(data.length & 0xff);
        }
        frame.write(data);
        out.write(frame.toByteArray());
        out.flush();
    }

    // ── tests ──────────────────────────────────────────────────────────────────────────────────────────

    private static LoadTestRunner runner(String mode) {
        assumeThat(LoadTestRunner.findK6(null)).as("k6 binary (K6_BIN or PATH)").isPresent();
        return LoadTestRunner.suite(suite).mode(mode).env("SEED", "false").env("VALIDATE_RESPONSES", "off")
                .env("ITERATIONS", "1")
                .env("BASE_URL", "http://127.0.0.1:" + server.getLocalPort() + "/shop");
    }

    private static ObjectNode config() throws IOException {
        return (ObjectNode) Documents.parse(Files.readString(suite.resolve("loadtest.config.json")));
    }

    @Test
    void channelsAreFoundInTheCode() throws IOException {
        JsonNode channels = Documents.parse(Files.readString(suite.resolve("data/channels.json")));
        List<String> ids = new ArrayList<>();
        channels.forEach(c -> ids.add(c.path("id").asString()));
        assertThat(ids).containsExactlyInAnyOrder("ws_ws_echo", "stomp_stomp", "sse_stream", "kafka_orders");
        JsonNode stomp = null;
        for (JsonNode c : channels) {
            if (c.path("kind").asString().equals("stomp")) {
                stomp = c;
            }
        }
        assertThat(stomp.path("send").get(0).asString()).isEqualTo("/app/chat");
        assertThat(stomp.path("subscribe").get(0).asString()).isEqualTo("/topic/chat");
        JsonNode cfg = config().path("channels");
        assertThat(cfg.path("stomp_stomp").path("message").path("user").asString()).isEqualTo("sample");
        assertThat(cfg.path("stomp_stomp").path("expectReply").asBoolean()).isTrue();
        assertThat(cfg.path("kafka_orders").path("message").path("quantity").asInt()).isEqualTo(1);
        assertThat(Documents.parse(pristine).path("apis").path("stream").path("enabled").asBoolean()).as("endless: not in per-API load")
                .isFalse();
        String kafka = Files.readString(suite.resolve("kafka.js"));
        assertThat(kafka).contains("k6/x/kafka").contains("\"orders\"").contains("broker:9092");
    }

    @Test
    void webSocketStompAndSseAreExercised() {
        LoadTestRunner.RunResult r = runner("channels-smoke").output(l -> { }).run();
        assertThat(r.passed()).as(r.output()).isTrue();
        assertThat(RECEIVED).filteredOn(m -> m.startsWith("ws ")).hasSize(3); // 3 messages in the one iteration
        assertThat(RECEIVED).anyMatch(m -> m.startsWith("stomp CONNECT"))
                .anyMatch(m -> m.startsWith("stomp SUBSCRIBE|id:sub-0|destination:/topic/chat"))
                .anyMatch(m -> m.startsWith("stomp SEND|destination:/app/chat|content-type:application/json")
                        && m.contains("\"user\":\"sample\""));
        assertThat(SSE_CONNECTIONS.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void aSilentWebSocketFailsWhenAReplyIsExpected() throws IOException {
        ObjectNode c = config();
        ((ObjectNode) c.path("channels").path("ws_ws_echo")).put("expectReply", true);
        Files.writeString(suite.resolve("loadtest.config.json"), c.toString());
        silent = true;
        LoadTestRunner.RunResult r = runner("channels-smoke").output(l -> { }).run();
        assertThat(r.passed()).as("no reply to the messages").isFalse();
    }

    @Test
    void formBodiesAreSentUrlEncoded() {
        LoadTestRunner.RunResult r = runner("smoke").env("API", "subscribe").output(l -> { }).run();
        assertThat(r.passed()).as(r.output()).isTrue();
        assertThat(FORMS.get()).as("application/x-www-form-urlencoded with email=…").isEqualTo(1);
    }

    @Test
    void graphQlOperationsAreGeneratedAndAnErrorsMemberFailsTheCheck() throws IOException {
        JsonNode apis = config().path("apis");
        assertThat(apis.propertyNames()).contains("gql_product", "gql_products", "gql_addProduct", "gql_deleteProduct");
        assertThat(apis.path("gql_deleteProduct").path("enabled").asBoolean()).as("destructive mutation off").isFalse();
        assertThat(apis.path("gql_product").path("weight").asInt()).isGreaterThan(apis.path("gql_addProduct").path("weight").asInt());
        LoadTestRunner.RunResult r = runner("smoke").env("API", "gql_product,gql_products,gql_addProduct")
                .env("READ_ONLY", "true").output(l -> { }).run();
        assertThat(r.passed()).as(r.output()).isTrue();
        assertThat(GRAPHQL).as("READ_ONLY still runs queries, never mutations").isNotEmpty()
                .allMatch(g -> g.startsWith("product ") || g.startsWith("products "));
        assertThat(GRAPHQL.stream().filter(g -> g.startsWith("product ")).findFirst().orElseThrow())
                .contains("query product($id: ID!) { product(id: $id) { id name price category { id label } tags } }");
        assertThat(GRAPHQL.stream().filter(g -> g.startsWith("products ")).findFirst().orElseThrow())
                .contains("$filter: ProductFilter").contains("$limit: Int").contains("products(filter: $filter, limit: $limit)");
    }
}
