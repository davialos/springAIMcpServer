package com.springaimcpservercommon.loadtest.cli;

import com.springaimcpservercommon.loadtest.api.LoadTestGenerator;
import com.springaimcpservercommon.loadtest.api.LoadTestReport;
import com.springaimcpservercommon.loadtest.api.LoadTestRunner;
import com.springaimcpservercommon.loadtest.api.ReportComparison;
import com.springaimcpservercommon.loadtest.data.BulkLoader;
import com.springaimcpservercommon.loadtest.data.DataPlan;
import com.springaimcpservercommon.loadtest.data.DatabaseSnapshot;
import com.springaimcpservercommon.loadtest.data.FieldPlan;
import com.springaimcpservercommon.loadtest.data.RecordedTraffic;
import com.springaimcpservercommon.loadtest.data.SeedPlan;
import com.springaimcpservercommon.loadtest.data.UserData;
import com.springaimcpservercommon.loadtest.discovery.ProjectSettings;
import com.springaimcpservercommon.loadtest.k6.LoadMode;
import com.springaimcpservercommon.loadtest.model.ApiCatalog;
import com.springaimcpservercommon.loadtest.model.ApiEndpoint;
import com.springaimcpservercommon.loadtest.model.ArraySchema;
import com.springaimcpservercommon.loadtest.model.ObjectSchema;
import com.springaimcpservercommon.loadtest.model.RefSchema;
import com.springaimcpservercommon.loadtest.model.Schema;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.springaimcpservercommon.loadtest.traffic.Route;
import com.springaimcpservercommon.loadtest.traffic.TrafficImporter;
import com.springaimcpservercommon.loadtest.traffic.TrafficModel;
import com.springaimcpservercommon.loadtest.traffic.TrafficReader;
import com.springaimcpservercommon.loadtest.discovery.Documents;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.springaimcpservercommon.loadtest.observe.JfrRecorder;
import com.springaimcpservercommon.loadtest.observe.ServerChecks;
import tools.jackson.databind.JsonNode;
import java.util.Set;

/**
 * {@code loadtest} command line: point it at a Spring Boot project, get a k6 suite.
 * <pre>
 * loadtest discover --project ../shop [--openapi URL|file] [--actuator URL|file]
 * loadtest generate --project ../shop [--openapi …] [--db-url jdbc:…] [--harvest] [--user-data values.json]
 *                   [--value email=a@b.test,c@d.test] [--bind '*.customerId=customers.id'] [--interactive]
 * loadtest run      --suite ../shop/load-tests --mode mixed-spike [--data-mode mixed] [--api getUser] [-- k6 args]
 * loadtest report   --suite ../shop/load-tests [--mode smoke]
 * loadtest compare  --baseline reports/base.json [--current reports/new.json] [--max-p95-increase 20]
 * loadtest modes
 * </pre>
 * The commands are a thin layer over the public API in {@code com.springaimcpservercommon.loadtest.api}.
 */
public final class LoadTestCli {

    private static final Set<String> FLAGS = Set.of("harvest", "interactive", "drop-unverified", "no-db",
            "no-default-excludes", "json", "help", "verbose", "read-only", "har-no-values", "no-bundled-openapi",
            "grafana", "force", "yes", "allow-prod", "apply-slo", "jfr", "no-server-checks");

    private final PrintStream out;
    private final PrintStream err;
    private final InputStream in;

    /**
     * Creates a CLI bound to the given streams (tests pass their own).
     *
     * @param out standard output
     * @param err diagnostics
     * @param in  standard input (interactive mode)
     */
    public LoadTestCli(PrintStream out, PrintStream err, InputStream in) {
        this.out = out;
        this.err = err;
        this.in = in;
    }

    /**
     * Entry point.
     *
     * @param args command and options
     */
    public static void main(String[] args) {
        System.exit(new LoadTestCli(System.out, System.err, System.in).execute(args));
    }

    /**
     * Executes a command.
     *
     * @param args command and options
     * @return exit code: 0 success, 1 failure, 2 usage error, otherwise k6's exit code
     */
    public int execute(String[] args) {
        CliArgs a;
        try {
            a = CliArgs.parse(args, FLAGS);
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            usage();
            return 2;
        }
        try {
            return switch (a.command()) {
                case "discover" -> discover(a);
                case "generate" -> generate(a);
                case "run" -> run(a);
                case "report" -> report(a);
                case "compare" -> compare(a);
                case "init-gradle" -> initGradle(a);
                case "traffic" -> traffic(a);
                case "bulk-load" -> bulkLoad(a);
                case "db-snapshot" -> dbSnapshot(a);
                case "modes" -> modes();
                case "help", "--help", "-h" -> {
                    usage();
                    yield 0;
                }
                default -> {
                    err.println("error: unknown command " + a.command());
                    usage();
                    yield 2;
                }
            };
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            return 2;
        } catch (RuntimeException e) {
            err.println("error: " + e.getMessage());
            if (a.flag("verbose")) {
                e.printStackTrace(err);
            }
            return 1;
        }
    }

    private void log(String line) {
        err.println("[loadtest] " + line);
    }

    // ── discover ───────────────────────────────────────────────────────────────────────────────────────

    /** The generator configured from the discovery and generation options. */
    private LoadTestGenerator generator(CliArgs a) {
        if (a.get("project") == null && a.all("openapi").isEmpty() && a.all("har").isEmpty()
                && a.get("actuator") == null) {
            throw new IllegalArgumentException("give at least one of --project, --openapi, --har, --actuator");
        }
        LoadTestGenerator.Builder b = LoadTestGenerator.builder().log(this::log);
        if (a.get("project") != null) {
            Path project = Path.of(a.get("project"));
            if (!Files.isDirectory(project)) {
                throw new IllegalArgumentException("--project " + project + " is not a directory");
            }
            b.project(project);
        }
        a.all("openapi").forEach(b::openApi);
        b.bundledOpenApi(!a.flag("no-bundled-openapi"));
        if (a.get("actuator") != null) {
            b.actuator(a.get("actuator"));
        }
        a.all("har").forEach(b::har);
        a.all("har-host").forEach(b::harHost);
        b.harValues(!a.flag("har-no-values"));
        a.all("include").forEach(b::include);
        a.all("exclude").forEach(b::exclude);
        b.defaultExcludes(!a.flag("no-default-excludes"));
        headers(a).forEach(b::header);
        if (a.get("out") != null) {
            b.outDir(Path.of(a.get("out")));
        }
        if (a.get("base-url") != null) {
            b.baseUrl(a.get("base-url"));
        }
        b.dataMode(a.get("data-mode", "auto"));
        if (a.flag("no-db")) {
            b.noDatabase();
        } else if (a.get("db-url") != null) {
            b.database(a.get("db-url"), a.get("db-user"), a.get("db-password"));
        }
        if (a.get("db-schema") != null) {
            b.databaseSchema(a.get("db-schema"));
        }
        b.sampleSize(a.integer("sample-size", 200));
        b.harvest(a.flag("harvest"));
        a.all("user-data").forEach(f -> b.userData(Path.of(f)));
        for (String v : a.all("value")) {
            int eq = v.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("--value expects key=v1,v2: " + v);
            }
            b.value(v.substring(0, eq), typedList(v.substring(eq + 1)));
        }
        for (String bind : a.all("bind")) {
            int eq = bind.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("--bind expects key=table.column: " + bind);
            }
            b.bind(bind.substring(0, eq), bind.substring(eq + 1));
        }
        b.dropUnverified(a.flag("drop-unverified"));
        b.auth(a.get("auth", "auto"), a.get("login-path"));
        if (a.flag("interactive")) {
            b.prompt(this::interactive);
        }
        return b.build();
    }

    private int discover(CliArgs a) {
        LoadTestGenerator.DiscoveryResult d = generator(a).discover();
        DataPlan plan = d.plan();
        SeedPlan seed = d.seed();
        out.println("Project: " + d.catalog().project() + (d.catalog().basePath() != null
                ? " (base path " + d.catalog().basePath() + ")" : ""));
        out.println();
        out.printf("%-36s %-7s %-50s %s%n", "API", "METHOD", "PATH", "SOURCES");
        for (ApiEndpoint e : d.catalog().endpoints()) {
            out.printf("%-36s %-7s %-50s %s%n", e.id(), e.method(), e.path(), String.join(",", e.sources()));
        }
        out.println();
        out.println(d.catalog().schemas().size() + " request schemas, " + d.catalog().entities().size()
                + " JPA entities, " + plan.fields().size() + " fields, " + plan.pools().size()
                + " real-data bindings");
        if (!seed.steps().isEmpty()) {
            out.println();
            out.println("Seeding order (entity relationships, parents first):");
            int i = 1;
            for (SeedPlan.Step st : seed.steps()) {
                out.printf("  %d. %-20s via %-24s%s%n", i++, st.table(), st.api(),
                        st.dependsOn().isEmpty() ? "" : " needs " + String.join(", ", st.dependsOn()));
            }
        }
        RecordedTraffic recorded = d.recordedTraffic();
        if (recorded != null) {
            out.println(recorded.journey().size() + " recorded calls replayable as a journey, "
                    + recorded.userData().fields().size() + " fields with recorded values");
        }
        if (a.flag("json")) {
            out.println();
            for (FieldPlan f : plan.fields().values()) {
                out.println(f.key() + "  kind=" + f.kind().generator()
                        + (f.pool() != null ? "  real=" + f.pool().key() : "") + (f.sensitive() ? "  sensitive" : ""));
            }
        }
        return 0;
    }

    // ── generate ───────────────────────────────────────────────────────────────────────────────────────

    private int generate(CliArgs a) {
        LoadTestGenerator.GenerationResult r = generator(a).generate();
        out.println("Generated k6 suite in " + r.outDir().toAbsolutePath().normalize());
        out.println("  " + r.apis() + " APIs, " + r.fields() + " fields, " + r.pools() + " real-data pools");
        out.println("  next: cd " + r.outDir() + " && ./run.sh smoke     (or: loadtest run --suite "
                + r.outDir() + " --mode mixed-load)");
        return 0;
    }

    private static List<Object> typedList(String csv) {
        List<Object> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t.matches("-?\\d{1,18}") ? (Object) Long.valueOf(t) : t);
            }
        }
        return out;
    }

    /** Asks, API by API, for values of each field; Enter skips. Works with a terminal or piped input. */
    private UserData interactive(ApiCatalog catalog, DataPlan plan, UserData user) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        UserData result = user;
        try {
            for (ApiEndpoint e : catalog.endpoints()) {
                out.print("Provide values for " + e.id() + " (" + e.method() + " " + e.path() + ")? [y/N] ");
                out.flush();
                String answer = reader.readLine();
                if (answer == null) {
                    break;
                }
                if (!answer.trim().toLowerCase(Locale.ROOT).startsWith("y")) {
                    continue;
                }
                for (FieldPlan f : fieldsOf(e, catalog, plan)) {
                    out.print("  " + f.key() + " [" + f.kind().generator()
                            + (f.pool() != null ? ", real " + f.pool().key() : "") + "] values (comma-separated): ");
                    out.flush();
                    String line = reader.readLine();
                    if (line == null) {
                        return result;
                    }
                    if (!line.isBlank()) {
                        result = result.merge(new UserData(Map.of(f.key(), typedList(line)), Map.of(), Map.of()));
                    }
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return result;
    }

    private static List<FieldPlan> fieldsOf(ApiEndpoint e, ApiCatalog catalog, DataPlan plan) {
        Set<String> owners = new HashSet<>();
        owners.add(e.id());
        if (e.body() != null) {
            collectRefs(e.body(), catalog, owners);
        }
        List<FieldPlan> out = new ArrayList<>();
        for (FieldPlan f : plan.fields().values()) {
            if (owners.contains(f.owner())) {
                out.add(f);
            }
        }
        return out;
    }

    private static void collectRefs(Schema s, ApiCatalog catalog, Set<String> owners) {
        switch (s) {
            case RefSchema(String name) -> {
                if (owners.add(name) && catalog.schemas().containsKey(name)) {
                    collectRefs(catalog.schemas().get(name), catalog, owners);
                }
            }
            case ArraySchema arr -> collectRefs(arr.items(), catalog, owners);
            case ObjectSchema o -> o.properties().values().forEach(p -> collectRefs(p.schema(), catalog, owners));
            default -> { }
        }
    }

    // ── run / modes ────────────────────────────────────────────────────────────────────────────────────

    private int run(CliArgs a) {
        Path suite = Path.of(a.get("suite", "load-tests"));
        LoadTestRunner runner = LoadTestRunner.suite(suite).mode(a.get("mode", "smoke")).output(out::println);
        if (a.get("data-mode") != null) {
            runner.dataMode(a.get("data-mode"));
        }
        Map<String, String> mapping = Map.of("api", "API", "vus", "VUS", "rate", "RATE", "duration-scale",
                "DURATION_SCALE", "base-url", "BASE_URL", "per-api", "PER_API", "preview-count", "PREVIEW_COUNT", "model", "MODEL",
                "warmup", "WARMUP");
        mapping.forEach((opt, envName) -> {
            if (a.get(opt) != null) {
                runner.env(envName, a.get(opt));
            }
        });
        if (a.flag("read-only")) {
            runner.env("READ_ONLY", "true");
        }
        if (a.flag("grafana") || a.get("prometheus-url") != null) {
            runner.grafana(a.get("prometheus-url", "http://localhost:9090/api/v1/write"));
            runner.grafanaAnnotations(a.get("grafana-url", "http://localhost:3000"), System.getenv("GRAFANA_TOKEN"));
        }
        if (a.get("k6") != null) {
            runner.k6(a.get("k6"));
        }
        a.passThrough().forEach(runner::k6Arg);
        serverChecks(a, suite, runner);
        if (a.flag("jfr")) {
            runner.jfr(new JfrRecorder.Settings(a.get("jvm-pid"), a.get("jvm-match"), a.get("jcmd", "jcmd"),
                    a.get("jfr-settings", "profile"), suite.resolve("reports"), a.all("jfr-package")));
        }
        if (a.get("restore-snapshot") != null) { // comparable runs start from the same data
            Jdbc j = jdbc(a);
            try (var snapshots = DatabaseSnapshot.connect(j.url(), j.user(),
                    j.password(), a.get("db-schema"), this::log)) {
                snapshots.allowProduction(a.flag("allow-prod")).restore(a.get("restore-snapshot"));
            } catch (SQLException e) {
                throw new IllegalStateException("restoring snapshot failed: " + e.getMessage(), e);
            }
        }
        LoadTestRunner.RunResult r = runner.run();
        if (!a.all("baseline").isEmpty() && r.report().isPresent()) {
            ReportComparison c = ReportComparison.compare(LoadTestReport.read(Path.of(a.get("baseline"))),
                    r.report().get(), rules(a));
            out.println(c.toMarkdown());
            if (r.exitCode() == 0 && !c.passed()) {
                return 3;
            }
        }
        return r.exitCode();
    }

    /** Server-side checks: on when the target's Prometheus endpoint answers (--no-server-checks, serverChecks.enabled). */
    private void serverChecks(CliArgs a, Path suite, LoadTestRunner runner) {
        JsonNode config;
        try {
            config = Documents.parse(Files.readString(suite.resolve("loadtest.config.json")));
        } catch (IOException e) {
            return;
        }
        JsonNode block = config.path("serverChecks");
        if (a.flag("no-server-checks") || !block.path("enabled").asBoolean(true) || a.get("mode") != null
                && a.get("mode").contains("preview")) {
            return;
        }
        String base = a.get("base-url", System.getenv().getOrDefault("BASE_URL", config.path("baseUrl").asString("")));
        String url = a.get("server-checks", block.path("url").asString("").isEmpty()
                ? base.replaceAll("/+$", "") + "/actuator/prometheus" : block.path("url").asString());
        if (base.isEmpty() && a.get("server-checks") == null) {
            return;
        }
        Map<String, String> headers = new java.util.LinkedHashMap<>(headers(a));
        a.all("server-header").forEach(h -> {
            int i = h.indexOf(':');
            headers.put(h.substring(0, i).strip(), h.substring(i + 1).strip());
        });
        String token = System.getenv("AUTH_TOKEN");
        if (token != null && !headers.containsKey("Authorization")
                && java.net.URI.create(url).getHost() != null && java.net.URI.create(url).getHost()
                .equals(java.net.URI.create(base.isEmpty() ? url : base).getHost())) {
            headers.put("Authorization", "Bearer " + token); // only to the target's own host
        }
        runner.serverChecks(new LoadTestRunner.ServerWatch(url, headers,
                java.time.Duration.ofSeconds(Long.parseLong(a.get("server-interval",
                        String.valueOf(block.path("intervalSeconds").asInt(5))))),
                ServerChecks.Settings.from(block)));
    }

    private int report(CliArgs a) {
        Path suite = Path.of(a.get("suite", "load-tests"));
        LoadTestReport r = a.get("file") != null ? LoadTestReport.read(Path.of(a.get("file")))
                : LoadTestReport.latest(suite, a.get("mode")).orElseThrow(() -> new IllegalArgumentException(
                "no report in " + suite.resolve("reports") + (a.get("mode") != null ? " for " + a.get("mode") : "")));
        out.println("Report " + r.file());
        out.printf(Locale.ROOT, "mode %s, data %s, target %s: %d requests, %.1f req/s, failed %s, p95 %s ms%n",
                r.mode(), r.dataMode(), r.baseUrl(), r.total().requests(), r.total().rps(),
                r.total().failedRate() == null ? "-" : String.format(Locale.ROOT, "%.2f%%", r.total().failedRate() * 100),
                r.total().p95Ms() == null ? "-" : String.format(Locale.ROOT, "%.1f", r.total().p95Ms()));
        for (LoadTestReport.ApiStats s : r.apis()) {
            out.printf(Locale.ROOT, "  %-32s %8d req  failed %7s  p95 %8s ms%n", s.api(), s.requests(),
                    s.failedRate() == null ? "-" : String.format(Locale.ROOT, "%.2f%%", s.failedRate() * 100),
                    s.p95Ms() == null ? "-" : String.format(Locale.ROOT, "%.1f", s.p95Ms()));
        }
        out.println(r.thresholdsPassed() ? "All thresholds passed." : "Thresholds FAILED: " + r.failedThresholds());
        return r.thresholdsPassed() ? 0 : 1;
    }

    private int compare(CliArgs a) {
        if (a.get("baseline") == null) {
            throw new IllegalArgumentException("--baseline <report.json> is required");
        }
        LoadTestReport baseline = LoadTestReport.read(Path.of(a.get("baseline")));
        LoadTestReport current = a.get("current") != null ? LoadTestReport.read(Path.of(a.get("current")))
                : LoadTestReport.latest(Path.of(a.get("suite", "load-tests")), baseline.mode())
                .orElseThrow(() -> new IllegalArgumentException("no current report; pass --current"));
        ReportComparison c = ReportComparison.compare(baseline, current, rules(a));
        out.println(c.toMarkdown());
        return c.passed() ? 0 : 3;
    }

    /** Imports production traffic (Prometheus metrics, access logs) into a suite: endpoint mix, rate, sessions. */
    private int traffic(CliArgs a) {
        try {
            return importTraffic(a);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private int importTraffic(CliArgs a) throws IOException {
        Path suite = Path.of(a.get("suite", "load-tests"));
        List<Route> routes = TrafficImporter.routes(suite);
        String basePath = a.get("base-path");
        if (basePath == null) {
            String url = Documents.parse(Files.readString(suite.resolve("loadtest.config.json"))).path("baseUrl")
                    .asString("");
            String p = url.replaceFirst("^[a-z]+://[^/]*", "");
            basePath = p.isEmpty() || p.equals("/") ? null : p;
        }
        TrafficModel model = null;
        String source = null;
        if (a.get("metrics") != null) {
            source = a.get("metrics");
            model = TrafficReader.fromPrometheus(Documents.text(source, headers(a)), routes,
                    seconds(a.get("period", "0s")));
        } else if (a.get("access-log") != null) {
            source = a.get("access-log");
            try (var lines = Files.lines(Path.of(source))) {
                model = TrafficReader.fromAccessLog((Iterable<String>) lines::iterator, routes, basePath,
                        a.get("log-time-unit", "auto"));
            }
        } else {
            throw new IllegalArgumentException("--metrics <url|file> (Prometheus) or --access-log <file> is required");
        }
        if (model.totalRequests() == 0) {
            throw new IllegalArgumentException("no request matched the suite's APIs: check --base-path (" + basePath
                    + ") and that the source holds http_server_requests / access log lines");
        }
        TrafficImporter.apply(suite, model, source, new TrafficImporter.Options(a.flag("apply-slo"),
                Double.parseDouble(a.get("slo-headroom", "1.5")),
                a.get("rate") == null ? null : Double.valueOf(a.get("rate"))), out::println);
        out.println("Run it: ./run.sh mixed-production   (open model, observed mix and rate)"
                + (model.sessions() > 0 ? "   or   ./run.sh session-load   (observed sessions)" : ""));
        return 0;
    }

    private static double seconds(String duration) {
        double total = 0;
        Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)?)(ms|s|m|h|d)").matcher(duration);
        while (m.find()) {
            double v = Double.parseDouble(m.group(1));
            total += switch (m.group(2)) {
                case "ms" -> v / 1000;
                case "s" -> v;
                case "m" -> v * 60;
                case "h" -> v * 3600;
                default -> v * 86400;
            };
        }
        return total;
    }

    /** JDBC settings: --db-url/--db-user/--db-password, else the project's spring.datasource.*. */
    private record Jdbc(String url, @Nullable String user,
                        @Nullable String password) {
    }

    private static Jdbc jdbc(CliArgs a) {
        ProjectSettings settings = a.get("project") == null
                ? ProjectSettings.DEFAULTS
                : ProjectSettings.read(Path.of(a.get("project")));
        String url = a.get("db-url", settings.datasourceUrl() == null ? "" : settings.datasourceUrl());
        if (url.isBlank()) {
            throw new IllegalArgumentException("--db-url is required (or --project with spring.datasource.url)");
        }
        String password = a.get("db-password", System.getenv().getOrDefault("LOADTEST_DB_PASSWORD",
                settings.datasourcePassword() == null ? "" : settings.datasourcePassword()));
        return new Jdbc(url, a.get("db-user", settings.datasourceUsername()), password);
    }

    /** Inserts realistic data volume into a test database: explicit tables and counts, --yes required. */
    private int bulkLoad(CliArgs a) {
        if (a.all("rows").isEmpty()) {
            throw new IllegalArgumentException("--rows table=count is required (repeatable)");
        }
        Map<String, Long> rows = new LinkedHashMap<>();
        for (String r : a.all("rows")) {
            int eq = r.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("--rows expects table=count: " + r);
            }
            rows.put(r.substring(0, eq), Long.parseLong(r.substring(eq + 1).replace("_", "")));
        }
        Jdbc j = jdbc(a);
        String shown = j.url().replaceAll("password=[^&;]*", "password=***");
        if (!a.flag("yes")) {
            out.println("Would insert into " + shown + ": " + rows);
            out.println("This WRITES to the database. Repeat with --yes on a test database.");
            return 2;
        }
        try (BulkLoader loader =
                     BulkLoader.connect(j.url(), j.user(), j.password(),
                             a.get("db-schema"), this::log)) {
            loader.batchSize(a.integer("batch", 1000)).seed(a.integer("seed", 42)).allowProduction(a.flag("allow-prod"));
            var result = loader.load(rows);
            out.println("Inserted " + result.inserted() + " in " + result.took().toSeconds() + " s");
            return 0;
        } catch (SQLException e) {
            throw new IllegalStateException("bulk load failed: " + e.getMessage(), e);
        }
    }

    /** save | restore | list | drop a PostgreSQL snapshot of the application tables. */
    private int dbSnapshot(CliArgs a) {
        String action = a.get("action", a.passThrough().isEmpty() ? "list" : a.passThrough().getFirst());
        Jdbc j = jdbc(a);
        try (DatabaseSnapshot snapshots =
                     DatabaseSnapshot.connect(j.url(), j.user(), j.password(),
                             a.get("db-schema"), this::log)) {
            snapshots.allowProduction(a.flag("allow-prod"));
            String name = a.get("name", "baseline");
            switch (action) {
                case "save" -> out.println("Saved " + snapshots.save(name) + " tables as snapshot " + name);
                case "restore" -> out.println("Restored " + snapshots.restore(name) + " tables from snapshot " + name);
                case "drop" -> {
                    snapshots.drop(name);
                    out.println("Dropped snapshot " + name);
                }
                case "list" -> snapshots.list().forEach(out::println);
                default -> throw new IllegalArgumentException("db-snapshot --action save|restore|list|drop");
            }
            return 0;
        } catch (SQLException e) {
            throw new IllegalStateException("snapshot " + action + " failed: " + e.getMessage(), e);
        }
    }

    /** Writes {@code <project>/gradle/loadtest.gradle} (the Gradle tasks) unless it exists. */
    private int initGradle(CliArgs a) {
        Path project = Path.of(a.get("project", "."));
        Path script = project.resolve("gradle/loadtest.gradle");
        if (Files.exists(script) && !a.flag("force")) {
            out.println(script + " exists (--force to overwrite)");
            return 0;
        }
        try (InputStream in = LoadTestCli.class.getResourceAsStream("/loadtest/gradle/loadtest.gradle")) {
            if (in == null) {
                throw new IllegalStateException("missing resource /loadtest/gradle/loadtest.gradle");
            }
            Files.createDirectories(script.getParent());
            Files.write(script, in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        boolean kotlin = Files.exists(project.resolve("build.gradle.kts"));
        out.println("Wrote " + script);
        out.println("Add to " + (kotlin ? "build.gradle.kts:  apply(from = \"gradle/loadtest.gradle\")"
                : "build.gradle:  apply from: 'gradle/loadtest.gradle'"));
        out.println("Then: ./gradlew loadtestGenerate loadtestRun -Ploadtest.mode=smoke");
        return 0;
    }

    private static ReportComparison.Rules rules(CliArgs a) {
        ReportComparison.Rules d = ReportComparison.Rules.DEFAULTS;
        return new ReportComparison.Rules(
                a.get("max-p95-increase") == null ? d.maxP95IncreasePct() : Double.parseDouble(a.get("max-p95-increase")),
                a.get("min-p95-delta-ms") == null ? d.minP95IncreaseMs() : Double.parseDouble(a.get("min-p95-delta-ms")),
                a.get("max-failed-increase") == null ? d.maxFailedRateIncrease()
                        : Double.parseDouble(a.get("max-failed-increase")),
                a.integer("min-requests", (int) d.minRequests()));
    }

    private int modes() {
        out.println("Load modes (MODE): <profile> per API, mixed-<profile> weighted mix, journey-<profile> recorded flow");
        for (LoadMode m : LoadMode.values()) {
            out.printf("  %-12s mixed-%-12s journey-%-12s %s%n", m.id(), m.id(), m.id(), m.description());
        }
        out.println("  preview      journey-preview      build requests and print them, send nothing");
        out.println();
        out.println("Data modes (DATA_MODE): auto | dummy | random | real | user | mixed");
        return 0;
    }

    private static Map<String, String> headers(CliArgs a) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String h : a.all("header")) {
            int colon = h.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("--header expects Name: value");
            }
            headers.put(h.substring(0, colon).trim(), h.substring(colon + 1).trim());
        }
        String token = System.getenv("AUTH_TOKEN");
        if (token != null && !token.isBlank() && !headers.containsKey("Authorization")) {
            headers.put("Authorization", "Bearer " + token);
        }
        return headers;
    }

    private void usage() {
        out.println("""
                Usage: loadtest <command> [options]

                Commands
                  discover   list the APIs and fields found in a project
                  generate   write a k6 suite (APIs, data providers, modes) for a project
                  run        run a generated suite with k6
                  report     print the newest report of a suite (exit 1 when thresholds failed)
                  compare    compare a report with a baseline report (exit 3 on regression)
                  init-gradle  write gradle/loadtest.gradle (loadtestGenerate/Run/Compare tasks) into --project
                  bulk-load  insert data volume into a TEST database: --rows table=count … --yes [--batch 1000]
                             [--seed 42] (parents first, foreign/unique keys kept; --db-url or --project)
                  db-snapshot --action save|restore|list|drop [--name baseline]   (PostgreSQL test databases)
                  modes      list load modes and data modes

                Discovery (discover, generate)
                  --project <dir>           Spring Boot project: sources, application.yml, JPA entities
                  --openapi <url|file>      OpenAPI 3 document, e.g. http://localhost:8080/v3/api-docs (repeatable;
                                            default: specs bundled in the project, --no-bundled-openapi to skip)
                  --actuator <url|file>     /actuator/mappings of the running app (also sees dynamic routes)
                  --har <file>              browser recording: DevTools ▸ Network ▸ Export HAR (repeatable). Adds
                                            the API calls seen, their recorded values, and a replayable journey
                  --har-host <host>         keep calls to this host (default: the most-called host; repeatable)
                  --har-no-values           use the recording's APIs and journey order, not its values
                  --include <pattern>       keep only matching APIs: '/api/**', 'GET /orders/*', or an API id
                  --exclude <pattern>       drop matching APIs (defaults also drop /error, /actuator/**, docs and
                                            /dynamic-ai/admin/**; --no-default-excludes keeps them)
                  --header 'Name: value'    header for URL fetches and harvesting (AUTH_TOKEN env adds a bearer)

                Generation
                  --out <dir>               suite directory (default <project>/load-tests)
                  --base-url <url>          target (default http://localhost:<server.port><context-path>)
                  --data-mode <mode>        default data mode: auto | dummy | random | real | user | mixed
                  --db-url <jdbc-url>       database for real data (default: the project's spring.datasource.url)
                  --db-user, --db-password  credentials (or LOADTEST_DB_PASSWORD); --db-schema to narrow; --no-db
                  --sample-size <n>         real values per pool (default 200)
                  --harvest                 also fill real pools from the running API's collection endpoints
                  --user-data <file>        JSON/YAML {fields, payloads, bindings} or CSV (header = field keys)
                  --value key=v1,v2         user values for a field (repeatable)
                  --bind key=table.column   force a field to draw real values from a column (repeatable)
                  --interactive             prompt for user values, API by API
                  --drop-unverified         drop user values of id/FK fields that are not in the database
                  --auth <type>             auto (from Spring Security) | none | bearer | basic | apiKey | login | form | oauth2
                                    (--login-path /api/auth/login)

                Production traffic
                  traffic --suite <dir> --metrics <url|file> [--period 7d]   Prometheus http_server_requests (the
                                            /actuator/prometheus text, or the JSON of /api/v1/query); --period = what
                                            the counters cover, to derive a rate
                  traffic --suite <dir> --access-log <file> [--base-path /shop] [--log-time-unit auto|ms|s]
                                            common/combined or JSON-lines access log: mix, rate, peak, sessions
                  [--apply-slo [--slo-headroom 1.5]]  turn observed p95/error rate into thresholds; [--rate n] overrides
                                            the observed rate. Then: ./run.sh mixed-production | session-load

                Run
                  --suite <dir> --mode <mode> [--data-mode <mode>] [--api id1,id2] [--vus n] [--rate n]
                  [--duration-scale 0.1] [--base-url url] [--per-api parallel] [--read-only] [--k6 path]
                  [--model open] [--warmup 60s|off]   arrival-rate (open) model; warm-up phase left out of the verdict
                  [--grafana | --prometheus-url url]   stream metrics to the suite's Grafana stack (grafana/)
                  [--grafana-url url]                  annotate the run there (default http://localhost:3000)
                  [--baseline report.json]             also compare with a baseline (exit 3 on regression)
                  Server-side checks run by default when <base-url>/actuator/prometheus answers (exit 4 when the
                  target itself shows trouble: pool wait, GC, 5xx, logged errors; limits: config → serverChecks):
                  [--server-checks <url>] [--server-header 'N: v'] [--server-interval 5] [--no-server-checks]
                  [--jfr [--jvm-pid n | --jvm-match regex] [--jfr-settings profile] [--jcmd 'cmd'] [--jfr-package p]]
                                                       record the target's JVM (jcmd) and analyze the recording
                  [--restore-snapshot name --db-url …] restore a db-snapshot first (comparable runs)
                  [-- extra k6 args]

                Report / compare
                  --suite <dir> [--mode <mode>] [--file report.json]
                  --baseline <report.json> [--current <report.json>]
                  [--max-p95-increase 20] [--min-p95-delta-ms 10] [--max-failed-increase 0.01] [--min-requests 10]
                """);
    }
}
