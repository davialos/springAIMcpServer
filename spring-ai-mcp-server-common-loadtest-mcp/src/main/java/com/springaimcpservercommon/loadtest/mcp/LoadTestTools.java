package com.springaimcpservercommon.loadtest.mcp;

import com.springaimcpservercommon.loadtest.api.LoadTestGenerator;
import com.springaimcpservercommon.loadtest.api.LoadTestReport;
import com.springaimcpservercommon.loadtest.api.LoadTestRunner;
import com.springaimcpservercommon.loadtest.api.ReportComparison;
import com.springaimcpservercommon.loadtest.data.FieldPlan;
import com.springaimcpservercommon.loadtest.data.SeedPlan;
import com.springaimcpservercommon.loadtest.k6.LoadMode;
import com.springaimcpservercommon.loadtest.model.ApiEndpoint;
import io.modelcontextprotocol.spec.McpSchema;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The load-test tools an agent calls, independent of the MCP transport: each takes the call's JSON arguments and
 * returns a result with a short text summary and the full structured content. Every path argument is resolved
 * against a root directory and must stay inside it.
 */
public final class LoadTestTools {

    /** Longest run a tool call may start. */
    static final Duration MAX_RUN = Duration.ofHours(1);

    private final Path root;

    /**
     * Creates the tools.
     *
     * @param root directory every path argument is resolved against and confined to
     */
    public LoadTestTools(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * One tool: its description and handler.
     *
     * @param tool    MCP tool definition (name, description, input schema, annotations)
     * @param handler call handler
     */
    public record Tool(McpSchema.Tool tool, Function<Map<String, Object>, McpSchema.CallToolResult> handler) {
    }

    /**
     * Every tool.
     *
     * @return tools in a stable order
     */
    public List<Tool> tools() {
        return List.of(
                tool("loadtest_discover", "Discover a Spring project's APIs",
                        """
                        Reads a Spring Boot project (controllers, functional routes, Spring Data REST, OpenAPI, JPA \
                        entities, Flyway/schema.sql) and returns every API, the request fields with their data \
                        kind and real-data binding, and the order in which test data is seeded (parents first). \
                        Writes nothing.""",
                        schema(Map.of(
                                "project", str("Project directory, relative to the server root"),
                                "include", strings("Keep only matching APIs: '/api/**', 'GET /orders/*' or an API id"),
                                "exclude", strings("Drop matching APIs"),
                                "fields", bool("Also list every request field (can be long)")),
                                List.of("project")),
                        annotations(true, false, true, false), this::discover),
                tool("loadtest_generate", "Generate the k6 load-test suite",
                        """
                        Writes (or regenerates) the k6 suite for a project: request builders and data providers per \
                        API, relationship-ordered seeding through the create endpoints, load modes, a Grafana \
                        stack. Regeneration keeps the team's loadtest.config.json, data/user.json and hooks.js. \
                        Reads the project's database only when database=true.""",
                        schema(Map.of(
                                "project", str("Project directory, relative to the server root"),
                                "outDir", str("Suite directory (default <project>/load-tests)"),
                                "baseUrl", str("Target base URL with context path (default from application.yml)"),
                                "include", strings("Keep only matching APIs"),
                                "exclude", strings("Drop matching APIs"),
                                "dataMode", str("Default data mode: auto, dummy, random, real, user or mixed"),
                                "database", bool("Sample real values from spring.datasource (default false)"),
                                "values", Map.of("type", "object", "description",
                                        "User values per field key: {\"CreateOrderRequest.couponCode\": [\"A\"]}",
                                        "additionalProperties", Map.of("type", "array"))),
                                List.of("project")),
                        annotations(false, false, true, false), this::generate),
                tool("loadtest_run", "Run a load test with k6",
                        """
                        Runs a generated suite with k6 against its target and returns pass/fail, the per-API report \
                        and the end of k6's output. Sends real HTTP traffic and, unless SEED=false, creates test \
                        rows through the API: confirm the target with the user first. Suites refuse \
                        production-looking hosts. Start with mode 'smoke'.""",
                        schema(Map.of(
                                "suite", str("Suite directory, relative to the server root"),
                                "mode", Map.of("type", "string", "description", "Load mode", "default", "smoke",
                                        "enum", LoadMode.modeNames()),
                                "dataMode", str("auto, dummy, random, real, user or mixed"),
                                "baseUrl", str("Target base URL for this run"),
                                "api", str("Comma-separated API ids to run"),
                                "env", Map.of("type", "object", "description",
                                        "Suite variables: VUS, RATE, DURATION_SCALE, SEED, SEED_PER_TABLE, "
                                                + "SEED_CLEANUP, READ_ONLY …",
                                        "additionalProperties", Map.of("type", "string")),
                                "grafana", bool("Stream metrics to the suite's Prometheus/Grafana stack"),
                                "timeoutSeconds", Map.of("type", "integer", "minimum", 10, "maximum",
                                        MAX_RUN.toSeconds(), "default", 600)),
                                List.of("suite")),
                        annotations(false, false, false, true), this::run),
                tool("loadtest_report", "Read a load-test report",
                        "Returns the newest report of a suite (or of one mode): totals, per-API requests, failures, "
                                + "p95/p99 and failed thresholds.",
                        schema(Map.of(
                                "suite", str("Suite directory, relative to the server root"),
                                "mode", str("Load mode; empty for the newest report of any mode")),
                                List.of("suite")),
                        annotations(true, false, true, false), this::report),
                tool("loadtest_compare", "Compare a run with a baseline",
                        """
                        Compares a report with a baseline report API by API and lists regressions (p95 slower \
                        than allowed, more failures) and improvements. The CI regression gate.""",
                        schema(Map.of(
                                "baseline", str("Baseline report file"),
                                "current", str("Report to judge (default: the newest of the baseline's mode)"),
                                "suite", str("Suite directory for the default current report"),
                                "maxP95IncreasePct", Map.of("type", "number", "default", 20),
                                "maxFailedRateIncrease", Map.of("type", "number", "default", 0.01)),
                                List.of("baseline")),
                        annotations(true, false, true, false), this::compare),
                tool("loadtest_modes", "List load and data modes",
                        "Lists the load modes (smoke, load, stress, spike, soak, breakpoint; per API, mixed-…, "
                                + "journey-…) and the data modes.",
                        schema(Map.of(), List.of()), annotations(true, false, true, false), args -> modes()));
    }

    // ── handlers ───────────────────────────────────────────────────────────────────────────────────────

    McpSchema.CallToolResult discover(Map<String, Object> args) {
        return guarded(() -> {
            List<String> log = new ArrayList<>();
            LoadTestGenerator.Builder b = LoadTestGenerator.builder().project(path(args, "project")).noDatabase()
                    .log(log::add);
            list(args, "include").forEach(b::include);
            list(args, "exclude").forEach(b::exclude);
            LoadTestGenerator.DiscoveryResult d = b.build().discover();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("project", d.catalog().project());
            out.put("basePath", d.basePath());
            List<Map<String, Object>> apis = new ArrayList<>();
            for (ApiEndpoint e : d.catalog().endpoints()) {
                apis.add(ordered("id", e.id(), "method", e.method().name(), "path", e.path(), "sources", e.sources()));
            }
            out.put("apis", apis);
            out.put("seedOrder", seedSteps(d.seed()));
            out.put("fieldCount", d.plan().fields().size());
            out.put("realDataBindings", d.plan().pools().stream().map(p -> p.key()).toList());
            if (Boolean.TRUE.equals(args.get("fields"))) {
                List<Map<String, Object>> fields = new ArrayList<>();
                for (FieldPlan f : d.plan().fields().values()) {
                    fields.add(ordered("key", f.key(), "kind", f.kind().generator(), "real",
                            f.pool() == null ? null : f.pool().key(), "sensitive", f.sensitive()));
                }
                out.put("fields", fields);
            }
            out.put("log", log);
            String text = d.catalog().endpoints().size() + " APIs, " + d.plan().fields().size() + " fields, "
                    + d.seed().steps().size() + " seeded tables"
                    + (d.seed().steps().isEmpty() ? "" : " (" + String.join(" → ",
                    d.seed().steps().stream().map(SeedPlan.Step::table).toList()) + ")");
            return ok(text, out);
        });
    }

    McpSchema.CallToolResult generate(Map<String, Object> args) {
        return guarded(() -> {
            List<String> log = new ArrayList<>();
            Path project = path(args, "project");
            LoadTestGenerator.Builder b = LoadTestGenerator.builder().project(project).log(log::add)
                    .outDir(args.get("outDir") != null ? path(args, "outDir") : project.resolve("load-tests"));
            if (!Boolean.TRUE.equals(args.get("database"))) {
                b.noDatabase();
            }
            if (args.get("baseUrl") instanceof String url && !url.isBlank()) {
                b.baseUrl(url);
            }
            if (args.get("dataMode") instanceof String dm && !dm.isBlank()) {
                b.dataMode(dm);
            }
            list(args, "include").forEach(b::include);
            list(args, "exclude").forEach(b::exclude);
            if (args.get("values") instanceof Map<?, ?> values) {
                values.forEach((k, v) -> b.value(String.valueOf(k), v instanceof List<?> l ? l : List.of(v)));
            }
            LoadTestGenerator.GenerationResult r = b.build().generate();
            Map<String, Object> out = ordered("suite", root.relativize(r.outDir().toAbsolutePath().normalize())
                            .toString(), "baseUrl", r.baseUrl(), "apis", r.apis(), "fields", r.fields(),
                    "realDataPools", r.pools(), "journeySteps", r.journey());
            out.put("seedOrder", seedSteps(r.seed()));
            out.put("next", "loadtest_run with mode smoke; grafana: docker compose -f <suite>/grafana/"
                    + "docker-compose.yml up -d, then run with grafana=true");
            out.put("log", log);
            return ok("Generated " + r.apis() + " APIs into " + out.get("suite") + " (target " + r.baseUrl() + ")",
                    out);
        });
    }

    McpSchema.CallToolResult run(Map<String, Object> args) {
        return guarded(() -> {
            Path suite = path(args, "suite");
            String mode = args.get("mode") instanceof String m && !m.isBlank() ? m : "smoke";
            if (LoadTestRunner.findK6(null).isEmpty()) {
                return error("no k6 executable: install k6 (https://grafana.com/docs/k6/latest/set-up/install-k6/) "
                        + "or set K6_BIN for the MCP server");
            }
            List<String> lines = new ArrayList<>();
            LoadTestRunner runner = LoadTestRunner.suite(suite).mode(mode).output(line -> {
                synchronized (lines) {
                    lines.add(line);
                }
            });
            if (args.get("dataMode") instanceof String dm && !dm.isBlank()) {
                runner.dataMode(dm);
            }
            if (args.get("baseUrl") instanceof String url && !url.isBlank()) {
                runner.env("BASE_URL", url);
            }
            if (args.get("api") instanceof String api && !api.isBlank()) {
                runner.env("API", api);
            }
            if (args.get("env") instanceof Map<?, ?> env) {
                env.forEach((k, v) -> runner.env(String.valueOf(k), String.valueOf(v)));
            }
            if (Boolean.TRUE.equals(args.get("grafana"))) {
                runner.grafana("http://localhost:9090/api/v1/write")
                        .grafanaAnnotations("http://localhost:3000", System.getenv("GRAFANA_TOKEN"));
            }
            long seconds = args.get("timeoutSeconds") instanceof Number n ? n.longValue() : 600;
            runner.timeout(Duration.ofSeconds(Math.max(10, Math.min(seconds, MAX_RUN.toSeconds()))));
            LoadTestRunner.RunResult r = runner.run();
            Map<String, Object> out = ordered("passed", r.passed(), "exitCode", r.exitCode(),
                    "thresholdsFailed", r.thresholdsFailed(), "testId", r.testId());
            r.report().ifPresent(rep -> out.put("report", report(rep)));
            List<String> tail;
            synchronized (lines) {
                tail = List.copyOf(lines.subList(Math.max(0, lines.size() - 60), lines.size()));
            }
            out.put("outputTail", tail);
            String text = (r.passed() ? "PASSED" : r.exitCode() == 124 ? "TIMED OUT"
                    : r.thresholdsFailed() ? "THRESHOLDS FAILED" : "FAILED (k6 exit " + r.exitCode() + ")")
                    + " — " + mode + r.report().map(rep -> String.format(java.util.Locale.ROOT,
                    ": %d requests, failed %s, p95 %s ms", rep.total().requests(),
                    pct(rep.total().failedRate()), num(rep.total().p95Ms()))).orElse("");
            return ok(text, out);
        });
    }

    McpSchema.CallToolResult report(Map<String, Object> args) {
        return guarded(() -> {
            Path suite = path(args, "suite");
            String mode = args.get("mode") instanceof String m && !m.isBlank() ? m : null;
            LoadTestReport r = LoadTestReport.latest(suite, mode).orElse(null);
            if (r == null) {
                return error("no report in " + root.relativize(suite.resolve("reports"))
                        + (mode == null ? "" : " for mode " + mode) + "; run loadtest_run first");
            }
            Map<String, Object> out = report(r);
            return ok(r.mode() + ": " + r.total().requests() + " requests, failed " + pct(r.total().failedRate())
                    + ", p95 " + num(r.total().p95Ms()) + " ms, thresholds "
                    + (r.thresholdsPassed() ? "passed" : "FAILED " + r.failedThresholds()), out);
        });
    }

    McpSchema.CallToolResult compare(Map<String, Object> args) {
        return guarded(() -> {
            LoadTestReport baseline = LoadTestReport.read(path(args, "baseline"));
            LoadTestReport current;
            if (args.get("current") instanceof String c && !c.isBlank()) {
                current = LoadTestReport.read(path(args, "current"));
            } else {
                Path suite = args.get("suite") != null ? path(args, "suite") : baseline.file().getParent().getParent();
                current = LoadTestReport.latest(suite, baseline.mode()).orElseThrow(() ->
                        new IllegalArgumentException("no " + baseline.mode() + " report to compare; pass current"));
            }
            ReportComparison.Rules d = ReportComparison.Rules.DEFAULTS;
            ReportComparison c = ReportComparison.compare(baseline, current, new ReportComparison.Rules(
                    args.get("maxP95IncreasePct") instanceof Number n ? n.doubleValue() : d.maxP95IncreasePct(),
                    d.minP95IncreaseMs(),
                    args.get("maxFailedRateIncrease") instanceof Number n ? n.doubleValue()
                            : d.maxFailedRateIncrease(),
                    d.minRequests()));
            List<Map<String, Object>> changes = new ArrayList<>();
            for (ReportComparison.Change ch : c.changes()) {
                changes.add(ordered("api", ch.api(), "status", ch.status(), "reason", ch.reason(),
                        "baselineP95Ms", ch.baselineP95Ms(), "currentP95Ms", ch.currentP95Ms(),
                        "baselineFailedRate", ch.baselineFailed(), "currentFailedRate", ch.currentFailed()));
            }
            Map<String, Object> out = ordered("passed", c.passed(), "regressions",
                    c.regressions().stream().map(ReportComparison.Change::api).toList(), "changes", changes,
                    "markdown", c.toMarkdown());
            return ok(c.passed() ? "No regression against " + baseline.file().getFileName()
                    : c.regressions().size() + " API(s) regressed: " + c.regressions().stream()
                    .map(ch -> ch.api() + " (" + ch.reason() + ")").toList(), out);
        });
    }

    McpSchema.CallToolResult modes() {
        List<Map<String, Object>> load = new ArrayList<>();
        for (LoadMode m : LoadMode.values()) {
            load.add(ordered("profile", m.id(), "perApi", m.id(), "mixed", "mixed-" + m.id(), "journey",
                    "journey-" + m.id(), "description", m.description()));
        }
        Map<String, Object> out = ordered("loadModes", load, "dataModes",
                List.of("auto", "dummy", "random", "real", "user", "mixed"), "other",
                List.of("preview", "journey-preview"));
        return ok("Load modes: " + String.join(", ", LoadMode.modeNames()), out);
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────────

    /** Resolves a path argument against the root and refuses anything outside it. */
    Path path(Map<String, Object> args, String name) {
        Object v = args.get(name);
        if (!(v instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        Path p = root.resolve(s).toAbsolutePath().normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException(name + " must be inside " + root);
        }
        try {
            if (Files.exists(p) && !p.toRealPath().startsWith(root.toRealPath())) {
                throw new IllegalArgumentException(name + " must be inside " + root + " (symbolic link)");
            }
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("cannot resolve " + name + ": " + e.getMessage(), e);
        }
        return p;
    }

    private static List<String> list(Map<String, Object> args, String name) {
        Object v = args.get(name);
        if (v instanceof List<?> l) {
            return l.stream().map(String::valueOf).toList();
        }
        return v instanceof String s && !s.isBlank() ? List.of(s) : List.of();
    }

    private static List<Map<String, Object>> seedSteps(SeedPlan seed) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SeedPlan.Step s : seed.steps()) {
            out.add(ordered("table", s.table(), "api", s.api(), "dependsOn", s.dependsOn(), "deleteApi",
                    s.deleteApi()));
        }
        return out;
    }

    private Map<String, Object> report(LoadTestReport r) {
        List<Map<String, Object>> apis = new ArrayList<>();
        for (LoadTestReport.ApiStats a : r.apis()) {
            apis.add(ordered("api", a.api(), "endpoint", a.name(), "requests", a.requests(), "rps", a.rps(),
                    "failedRate", a.failedRate(), "avgMs", a.avgMs(), "p95Ms", a.p95Ms(), "p99Ms", a.p99Ms(),
                    "maxMs", a.maxMs()));
        }
        return ordered("file", root.relativize(r.file().toAbsolutePath().normalize()).toString(), "mode", r.mode(),
                "dataMode", r.dataMode(), "baseUrl", r.baseUrl(), "requests", r.total().requests(),
                "rps", r.total().rps(), "failedRate", r.total().failedRate(), "p95Ms", r.total().p95Ms(),
                "thresholdsPassed", r.thresholdsPassed(), "failedThresholds", r.failedThresholds(), "apis", apis);
    }

    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                m.put((String) kv[i], kv[i + 1]);
            }
        }
        return m;
    }

    private static String pct(@Nullable Double v) {
        return v == null ? "-" : String.format(java.util.Locale.ROOT, "%.2f%%", v * 100);
    }

    private static String num(@Nullable Double v) {
        return v == null ? "-" : String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    private static McpSchema.CallToolResult ok(String text, Map<String, Object> structured) {
        return McpSchema.CallToolResult.builder().addTextContent(text).structuredContent(structured)
                .isError(false).build();
    }

    private static McpSchema.CallToolResult error(String message) {
        return McpSchema.CallToolResult.builder().addTextContent(message).isError(true).build();
    }

    /** Tool errors are results the agent can read and act on, never protocol errors. */
    private static McpSchema.CallToolResult guarded(java.util.function.Supplier<McpSchema.CallToolResult> body) {
        try {
            return body.get();
        } catch (IllegalArgumentException | IllegalStateException | java.io.UncheckedIOException e) {
            return error(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private static Tool tool(String name, String title, String description, Map<String, Object> inputSchema,
                             McpSchema.ToolAnnotations annotations,
                             Function<Map<String, Object>, McpSchema.CallToolResult> handler) {
        return new Tool(McpSchema.Tool.builder().name(name).title(title).description(description)
                .inputSchema(inputSchema).annotations(annotations).build(), handler);
    }

    private static McpSchema.ToolAnnotations annotations(boolean readOnly, boolean destructive, boolean idempotent,
                                                         boolean openWorld) {
        return new McpSchema.ToolAnnotations(null, readOnly, destructive, idempotent, openWorld, null);
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", new LinkedHashMap<>(properties));
        s.put("required", required);
        s.put("additionalProperties", false);
        return s;
    }

    private static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> bool(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    private static Map<String, Object> strings(String description) {
        return Map.of("type", "array", "items", Map.of("type", "string"), "description", description);
    }
}
