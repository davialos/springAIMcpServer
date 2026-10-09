package com.springaimcpservercommon.celfaker.server;

import com.springaimcpservercommon.celfaker.contract.ApiContract;
import com.springaimcpservercommon.celfaker.contract.ApiSpec;
import com.springaimcpservercommon.celfaker.data.FakeInput;
import com.springaimcpservercommon.celfaker.expr.CaseBuilder;
import com.springaimcpservercommon.celfaker.expr.CelCase;
import com.springaimcpservercommon.celfaker.expr.ExpressionFaker;
import com.springaimcpservercommon.celfaker.importer.CurlParser;
import com.springaimcpservercommon.celfaker.importer.Imported;
import com.springaimcpservercommon.celfaker.importer.OpenApiImporter;
import com.springaimcpservercommon.celfaker.importer.SpecFetcher;
import com.springaimcpservercommon.celfaker.payload.Candidate;
import com.springaimcpservercommon.celfaker.payload.JsonValues;
import com.springaimcpservercommon.celfaker.payload.PayloadAnalyzer;
import com.springaimcpservercommon.celfaker.pipeline.FakerLibrary;
import com.springaimcpservercommon.celfaker.pipeline.FakerPipeline;
import com.springaimcpservercommon.celfaker.values.AttributeValueMap;
import com.springaimcpservercommon.celfaker.values.ValueFactory;
import com.springaimcpservercommon.celfaker.workflow.Workflow;
import com.springaimcpservercommon.celfaker.workflow.WorkflowPlanner;
import com.springaimcpservercommon.ruleengine.cel.ParameterLibrary;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The flow dashboard's backend: serves the static UI and a JSON API over the pipeline. Stateless (every request
 * carries what it needs), bound to the loopback interface, and a developer tool — never to be exposed.
 *
 * <p>Guards against a web page in the developer's browser reaching it: the {@code Host} header must be a loopback name,
 * and POSTs must be {@code application/json} (which a cross-site page cannot send without a CORS preflight; none is
 * answered).
 */
public final class DashboardServer implements AutoCloseable {

    private static final int MAX_BODY = 8 * 1024 * 1024;
    private static final String UI_ROOT = "/META-INF/resources/celfaker/ui/";

    /**
     * A service running on this machine, as the local-dev control plane knows it.
     *
     * @param name display name
     * @param url  base URL
     */
    public record LocalService(String name, String url) {
    }

    private final HttpServer server;
    private final String csp;
    private final List<LocalService> localServices;

    private record AnalyzeRequest(JsonNode payload, @Nullable String object, @Nullable List<String> mapPaths) {
    }

    private record ExpressionRequest(List<Candidate> candidates, @Nullable AttributeValueMap valueMap, @Nullable Long seed,
                                     ExpressionFaker.@Nullable Options options) {
    }

    private record CurlRequest(String curl) {
    }

    private record OpenApiRequest(@Nullable String url, @Nullable String spec) {
    }

    private record FakeRequest(ApiSpec api, @Nullable Long seed, @Nullable Integer count) {
    }

    private record SendRequest(String method, String url, @Nullable Map<String, String> headers, @Nullable JsonNode body,
                               @Nullable Map<String, String> env) {
    }

    private record ValuesRequest(List<Candidate> candidates, @Nullable Long seed) {
    }

    private record CasesRequest(List<Candidate> candidates, @Nullable AttributeValueMap valueMap, @Nullable Long seed,
                                String expression, @Nullable Integer max) {
    }

    private record ProposeRequest(ApiContract contract) {
    }

    private record ValidateRequest(ApiContract contract, Workflow workflow) {
    }

    private record GenerateRequest(ApiContract contract, @Nullable Workflow workflow, @Nullable AttributeValueMap valueMap,
                                   @Nullable Long seed, @Nullable Integer validCount, ExpressionFaker.@Nullable Options options,
                                   @Nullable Integer casesPerExpression) {
    }

    /**
     * Starts the server.
     *
     * @param port TCP port on the loopback interface (0 = any free port)
     * @throws IOException when the port cannot be bound
     */
    public DashboardServer(int port) throws IOException {
        this(port, List.of(), List.of());
    }

    /**
     * Starts the server for embedding in another local dashboard.
     *
     * @param port           TCP port on the loopback interface (0 = any free port)
     * @param frameAncestors loopback origins ({@code http://127.0.0.1:8765}) allowed to show the UI in an iframe; others are rejected
     * @param localServices  other local services (name, base URL) offered as one-click import sources in the UI
     * @throws IOException when the port cannot be bound
     * @throws IllegalArgumentException when an origin is not a loopback http(s) origin or a service URL is not http(s)
     */
    public DashboardServer(int port, List<String> frameAncestors, List<LocalService> localServices) throws IOException {
        for (String o : frameAncestors) {
            if (!o.matches("https?://(localhost|127\\.0\\.0\\.1|\\[::1])(:\\d{1,5})?")) {
                throw new IllegalArgumentException("frame ancestor must be a loopback http(s) origin: " + o);
            }
        }
        for (LocalService l : localServices) {
            if (!l.url().matches("https?://[^\\s]+")) {
                throw new IllegalArgumentException("local service URL must be http(s): " + l.url());
            }
        }
        this.csp = "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; frame-ancestors "
                + (frameAncestors.isEmpty() ? "'none'" : String.join(" ", frameAncestors));
        this.localServices = List.copyOf(localServices);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    /**
     * The bound port.
     *
     * @return port
     */
    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!loopbackHost(ex.getRequestHeaders().getFirst("Host"))) {
                send(ex, 403, "text/plain", "forbidden host".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.startsWith("/api/")) {
                if ("POST".equals(method)) {
                    String ct = ex.getRequestHeaders().getFirst("Content-Type");
                    if (ct == null || !ct.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
                        sendJson(ex, 415, Map.of("error", "Content-Type must be application/json"));
                        return;
                    }
                }
                api(ex, method, path);
            } else if ("GET".equals(method)) {
                staticFile(ex, path);
            } else {
                send(ex, 405, "text/plain", "method not allowed".getBytes(StandardCharsets.UTF_8));
            }
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException e) {
            sendJson(ex, 400, Map.of("error", String.valueOf(e.getMessage())));
        } catch (RuntimeException e) {
            sendJson(ex, 500, Map.of("error", e.getClass().getSimpleName()));
        } finally {
            ex.close();
        }
    }

    private static boolean loopbackHost(@Nullable String host) {
        if (host == null) {
            return false;
        }
        String name = host.startsWith("[") ? host.substring(0, host.indexOf(']') + 1) : host.replaceAll(":\\d+$", "");
        return name.equals("localhost") || name.equals("127.0.0.1") || name.equals("[::1]");
    }

    private void api(HttpExchange ex, String method, String path) throws IOException {
        if ("GET".equals(method) && path.equals("/api/example")) {
            try (InputStream in = DashboardServer.class.getResourceAsStream("/celfaker/examples/shop-contract.json")) {
                send(ex, 200, "application/json", in.readAllBytes());
            }
            return;
        }
        if ("GET".equals(method) && path.equals("/api/local-services")) {
            sendJson(ex, 200, Map.of("services", localServices));
            return;
        }
        if (!"POST".equals(method)) {
            sendJson(ex, 405, Map.of("error", "POST expected"));
            return;
        }
        byte[] body = readBody(ex);
        switch (path) {
            case "/api/analyze" -> {
                AnalyzeRequest r = read(body, AnalyzeRequest.class);
                PayloadAnalyzer.Analysis a = PayloadAnalyzer.analyze(r.payload(), r.object() == null ? "root" : r.object(),
                        r.mapPaths() == null ? Set.of() : new HashSet<>(r.mapPaths()));
                sendJson(ex, 200, Map.of("candidates", a.candidates().stream().map(DashboardServer::view).toList(), "skipped", a.skipped()));
            }
            case "/api/expressions" -> {
                ExpressionRequest r = read(body, ExpressionRequest.class);
                long seed = r.seed() == null ? 42 : r.seed();
                ParameterLibrary lib = FakerLibrary.library(r.candidates());
                AttributeValueMap map = valueMap(r.valueMap(), r.candidates(), seed);
                ExpressionFaker.Result res = new ExpressionFaker(lib, map, seed)
                        .generate(r.options() == null ? ExpressionFaker.Options.defaults() : r.options());
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("expressions", res.expressions());
                out.put("rejected", res.rejected());
                out.put("attributeMap", map);
                sendJson(ex, 200, out);
            }
            case "/api/import/curl" -> sendJson(ex, 200, CurlParser.parse(read(body, CurlRequest.class).curl()));
            case "/api/import/openapi" -> {
                OpenApiRequest r = read(body, OpenApiRequest.class);
                Imported imported;
                if (r.spec() != null && !r.spec().isBlank()) {
                    imported = OpenApiImporter.parse(SpecFetcher.parse(r.spec()), r.url() == null ? "" : r.url());
                } else if (r.url() != null && !r.url().isBlank()) {
                    SpecFetcher.Fetched f = SpecFetcher.fetch(r.url());
                    imported = OpenApiImporter.parse(f.document(), f.source());
                } else {
                    throw new IllegalArgumentException("give a URL or paste the document");
                }
                sendJson(ex, 200, imported);
            }
            case "/api/fake" -> {
                FakeRequest r = read(body, FakeRequest.class);
                sendJson(ex, 200, FakeInput.generate(r.api(), r.seed() == null ? 42 : r.seed(), r.count() == null ? 5 : r.count()));
            }
            case "/api/send" -> sendJson(ex, 200, send(read(body, SendRequest.class)));
            case "/api/attribute-map" -> {
                ValuesRequest r = read(body, ValuesRequest.class);
                sendJson(ex, 200, new ValueFactory(r.seed() == null ? 42 : r.seed()).build(r.candidates()));
            }
            case "/api/cases" -> {
                CasesRequest r = read(body, CasesRequest.class);
                long seed = r.seed() == null ? 42 : r.seed();
                ParameterLibrary lib = FakerLibrary.library(r.candidates());
                List<CelCase> cases = new CaseBuilder(lib, valueMap(r.valueMap(), r.candidates(), seed), seed)
                        .build(r.expression(), r.max() == null ? 24 : Math.min(r.max(), 500));
                sendJson(ex, 200, Map.of("cases", cases));
            }
            case "/api/workflow/propose" -> sendJson(ex, 200, WorkflowPlanner.propose(read(body, ProposeRequest.class).contract()));
            case "/api/workflow/validate" -> {
                ValidateRequest r = read(body, ValidateRequest.class);
                sendJson(ex, 200, Map.of("problems", WorkflowPlanner.validate(r.workflow(), r.contract())));
            }
            case "/api/generate", "/api/generate.zip" -> {
                FakerPipeline.Output out = generate(read(body, GenerateRequest.class));
                if (path.endsWith(".zip")) {
                    sendZip(ex, out.files());
                } else {
                    sendJson(ex, 200, Map.of("files", out.files(), "warnings", out.warnings(), "summary", out.summary()));
                }
            }
            default -> sendJson(ex, 404, Map.of("error", "unknown endpoint"));
        }
    }

    /** Sends one request to the service under test, like a REST client; never follows redirects, caps the answer. */
    private static Map<String, Object> send(SendRequest r) {
        Map<String, String> env = r.env() == null ? Map.of() : r.env();
        String url = subst(r.url(), env);
        java.net.URI uri = java.net.URI.create(url.strip());
        if (uri.getHost() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
            throw new IllegalArgumentException("only absolute http(s) URLs can be sent: " + r.url());
        }
        if (!r.method().matches("GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS")) {
            throw new IllegalArgumentException("unsupported method " + r.method());
        }
        java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(15));
        boolean hasType = false;
        if (r.headers() != null) {
            for (Map.Entry<String, String> h : r.headers().entrySet()) {
                if (h.getKey().equalsIgnoreCase("host") || h.getKey().equalsIgnoreCase("content-length")) {
                    continue;
                }
                hasType |= h.getKey().equalsIgnoreCase("content-type");
                b.header(h.getKey(), subst(h.getValue(), env));
            }
        }
        boolean body = r.body() != null && !r.body().isMissingNode() && !r.body().isNull();
        if (body && !hasType) {
            b.header("Content-Type", "application/json");
        }
        b.method(r.method(), body ? java.net.http.HttpRequest.BodyPublishers.ofString(r.body().toString())
                : java.net.http.HttpRequest.BodyPublishers.noBody());
        Map<String, Object> out = new LinkedHashMap<>();
        long start = System.nanoTime();
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5))
                    .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
            java.net.http.HttpResponse<byte[]> res = client.send(b.build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            byte[] bytes = res.body();
            out.put("status", res.statusCode());
            out.put("millis", (System.nanoTime() - start) / 1_000_000);
            Map<String, String> headers = new LinkedHashMap<>();
            res.headers().map().forEach((k, v) -> headers.put(k, String.join(", ", v)));
            out.put("headers", headers);
            out.put("body", new String(bytes, 0, Math.min(bytes.length, 65_536), StandardCharsets.UTF_8));
            out.put("truncated", bytes.length > 65_536);
        } catch (IOException e) {
            out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.put("error", "interrupted");
        }
        List<String> unresolved = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{\\{env\\.([A-Za-z0-9_]+)}}").matcher(r.url() + String.valueOf(r.headers()));
        while (m.find()) {
            if (!env.containsKey(m.group(1)) && !unresolved.contains(m.group(1))) {
                unresolved.add(m.group(1));
            }
        }
        out.put("unresolvedEnv", unresolved);
        return out;
    }

    private static String subst(String text, Map<String, String> env) {
        String out = text;
        for (Map.Entry<String, String> e : env.entrySet()) {
            out = out.replace("{{env." + e.getKey() + "}}", e.getValue());
        }
        return out;
    }

    private static FakerPipeline.Output generate(GenerateRequest r) {
        FakerPipeline.Request d = FakerPipeline.Request.of(r.contract());
        return FakerPipeline.run(new FakerPipeline.Request(r.contract(), r.workflow(), r.valueMap(),
                r.seed() == null ? d.seed() : r.seed(),
                r.validCount() == null ? d.validCount() : Math.min(r.validCount(), 1000),
                r.options() == null ? d.expressionOptions() : r.options(),
                r.casesPerExpression() == null ? d.casesPerExpression() : Math.min(r.casesPerExpression(), 100)));
    }

    private static AttributeValueMap valueMap(@Nullable AttributeValueMap given, List<Candidate> candidates, long seed) {
        AttributeValueMap generated = new ValueFactory(seed).build(candidates);
        if (given == null) {
            return generated;
        }
        Map<String, com.springaimcpservercommon.celfaker.values.AttributeValues> merged = new LinkedHashMap<>(generated.attributes());
        given.attributes().forEach((k, v) -> merged.computeIfPresent(k, (kk, old) -> v));
        return new AttributeValueMap(AttributeValueMap.VERSION, seed, merged);
    }

    private static Map<String, Object> view(Candidate c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", c.path());
        m.put("name", c.celName());
        m.put("objectCode", c.objectCode());
        m.put("attributeCode", c.attributeCode());
        m.put("type", c.type().name());
        m.put("sample", c.sample());
        return m;
    }

    private static <T> T read(byte[] body, Class<T> type) {
        return JsonValues.MAPPER.readValue(body, type);
    }

    private static byte[] readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BODY) {
                    throw new IllegalArgumentException("request body larger than " + MAX_BODY + " bytes");
                }
            }
            return out.toByteArray();
        }
    }

    private void staticFile(HttpExchange ex, String path) throws IOException {
        String p = path.equals("/") ? "/index.html" : path;
        if (p.startsWith("/ui/")) {
            p = p.substring(3);
        }
        if (p.contains("..") || p.contains("//") || p.contains("\\")) {
            send(ex, 400, "text/plain", "bad path".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream in = DashboardServer.class.getResourceAsStream(UI_ROOT + p.substring(1))) {
            if (in == null) {
                send(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String type = p.endsWith(".html") ? "text/html; charset=utf-8" : p.endsWith(".js") ? "text/javascript; charset=utf-8"
                    : p.endsWith(".css") ? "text/css; charset=utf-8" : p.endsWith(".json") ? "application/json" : "application/octet-stream";
            ex.getResponseHeaders().set("Content-Security-Policy", csp);
            send(ex, 200, type, in.readAllBytes());
        }
    }

    private static void sendJson(HttpExchange ex, int status, Object value) throws IOException {
        send(ex, status, "application/json", JsonValues.MAPPER.writeValueAsBytes(value));
    }

    private static void sendZip(HttpExchange ex, Map<String, String> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> f : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(f.getKey()));
                zip.write(f.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"celfaker-suite.zip\"");
        send(ex, 200, "application/zip", bytes.toByteArray());
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }
}
