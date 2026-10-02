package com.springaimcpservercommon.loadtest.cli;

import com.springaimcpservercommon.loadtest.api.LoadTestGenerator;
import com.springaimcpservercommon.loadtest.api.LoadTestReport;
import com.springaimcpservercommon.loadtest.api.LoadTestRunner;
import com.springaimcpservercommon.loadtest.api.ReportComparison;
import com.springaimcpservercommon.loadtest.data.DataPlan;
import com.springaimcpservercommon.loadtest.data.FieldPlan;
import com.springaimcpservercommon.loadtest.data.RecordedTraffic;
import com.springaimcpservercommon.loadtest.data.SeedPlan;
import com.springaimcpservercommon.loadtest.data.UserData;
import com.springaimcpservercommon.loadtest.k6.LoadMode;
import com.springaimcpservercommon.loadtest.model.ApiCatalog;
import com.springaimcpservercommon.loadtest.model.ApiEndpoint;
import com.springaimcpservercommon.loadtest.model.ArraySchema;
import com.springaimcpservercommon.loadtest.model.ObjectSchema;
import com.springaimcpservercommon.loadtest.model.RefSchema;
import com.springaimcpservercommon.loadtest.model.Schema;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
            "grafana");

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
            if (!java.nio.file.Files.isDirectory(project)) {
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
        b.auth(a.get("auth", "none"), a.get("login-path"));
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
                "DURATION_SCALE", "base-url", "BASE_URL", "per-api", "PER_API", "preview-count", "PREVIEW_COUNT");
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
                  --auth <type>             none | bearer | basic | apiKey | login   (--login-path /api/auth/login)

                Run
                  --suite <dir> --mode <mode> [--data-mode <mode>] [--api id1,id2] [--vus n] [--rate n]
                  [--duration-scale 0.1] [--base-url url] [--per-api parallel] [--read-only] [--k6 path]
                  [--grafana | --prometheus-url url]   stream metrics to the suite's Grafana stack (grafana/)
                  [--grafana-url url]                  annotate the run there (default http://localhost:3000)
                  [--baseline report.json]             also compare with a baseline (exit 3 on regression)
                  [-- extra k6 args]

                Report / compare
                  --suite <dir> [--mode <mode>] [--file report.json]
                  --baseline <report.json> [--current <report.json>]
                  [--max-p95-increase 20] [--min-p95-delta-ms 10] [--max-failed-increase 0.01] [--min-requests 10]
                """);
    }
}
