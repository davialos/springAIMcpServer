package com.springaimcpservercommon.loadtest.cli;

import com.springaimcpservercommon.loadtest.data.ApiHarvester;
import com.springaimcpservercommon.loadtest.data.DataPlan;
import com.springaimcpservercommon.loadtest.data.DatabaseSampler;
import com.springaimcpservercommon.loadtest.data.FieldPlan;
import com.springaimcpservercommon.loadtest.data.PoolRef;
import com.springaimcpservercommon.loadtest.data.RealDataBinder;
import com.springaimcpservercommon.loadtest.data.RealDataCollector;
import com.springaimcpservercommon.loadtest.data.RecordedTraffic;
import com.springaimcpservercommon.loadtest.data.TableIndex;
import com.springaimcpservercommon.loadtest.data.UserData;
import com.springaimcpservercommon.loadtest.discovery.ActuatorMappingsReader;
import com.springaimcpservercommon.loadtest.discovery.CatalogMerger;
import com.springaimcpservercommon.loadtest.discovery.Documents;
import com.springaimcpservercommon.loadtest.discovery.HarCapture;
import com.springaimcpservercommon.loadtest.discovery.HarReader;
import com.springaimcpservercommon.loadtest.discovery.OpenApiReader;
import com.springaimcpservercommon.loadtest.discovery.ProjectSettings;
import com.springaimcpservercommon.loadtest.discovery.SpringSourceScanner;
import com.springaimcpservercommon.loadtest.k6.K6Runner;
import com.springaimcpservercommon.loadtest.k6.K6SuiteGenerator;
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
import java.util.Set;

/**
 * {@code loadtest} command line: point it at a Spring Boot project, get a k6 suite.
 * <pre>
 * loadtest discover --project ../shop [--openapi URL|file] [--actuator URL|file]
 * loadtest generate --project ../shop [--openapi …] [--db-url jdbc:…] [--harvest] [--user-data values.json]
 *                   [--value email=a@b.test,c@d.test] [--bind '*.customerId=customers.id'] [--interactive]
 * loadtest run      --suite ../shop/load-tests --mode mixed-spike [--data-mode mixed] [--api getUser] [-- k6 args]
 * loadtest modes
 * </pre>
 */
public final class LoadTestCli {

    private static final Set<String> FLAGS = Set.of("harvest", "interactive", "drop-unverified", "no-db",
            "no-default-excludes", "json", "help", "verbose", "read-only", "har-no-values", "no-bundled-openapi");

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

    private record Discovery(ApiCatalog catalog, ProjectSettings settings, @Nullable Path project,
                             List<HarCapture> recordings, @Nullable String basePath) {
    }

    private Discovery discoverCatalog(CliArgs a) {
        String project = a.get("project");
        Path projectDir = project == null ? null : Path.of(project);
        Map<String, String> headers = headers(a);
        List<ApiCatalog> catalogs = new ArrayList<>();
        ProjectSettings settings = ProjectSettings.DEFAULTS;
        if (projectDir != null) {
            if (!Files.isDirectory(projectDir)) {
                throw new IllegalArgumentException("--project " + project + " is not a directory");
            }
            settings = ProjectSettings.read(projectDir);
        }
        // Precedence: OpenAPI (authoritative contract) > sources (constraints, entities) > browser recordings
        // (observed shapes and values) > actuator (live routes)
        List<String> specs = new ArrayList<>(a.all("openapi"));
        if (specs.isEmpty() && projectDir != null && !a.flag("no-bundled-openapi")) {
            for (Path spec : SpringSourceScanner.bundledOpenApiSpecs(projectDir)) {
                log("openapi: using the project's bundled " + projectDir.relativize(spec));
                specs.add(spec.toString());
            }
        }
        for (String spec : specs) {
            ApiCatalog c = new OpenApiReader(this::log).read(Documents.text(spec, headers));
            catalogs.add(projectDir == null ? c : relativeToContext(c, settings.contextPath()));
        }
        if (projectDir != null) {
            catalogs.add(new SpringSourceScanner(this::log).scan(projectDir));
        }
        String basePath = basePath(a, settings, catalogs);
        List<HarCapture> recordings = new ArrayList<>();
        if (!a.all("har").isEmpty()) {
            List<String> known = new ArrayList<>();
            catalogs.forEach(c -> c.endpoints().forEach(e -> known.add(e.path())));
            for (String har : a.all("har")) {
                HarCapture capture = new HarReader(this::log).read(Documents.text(har, Map.of()),
                        new HarReader.Options(a.all("har-host"), basePath, known));
                recordings.add(capture);
                catalogs.add(capture.catalog());
            }
        }
        if (a.get("actuator") != null) {
            catalogs.add(new ActuatorMappingsReader(this::log).read(Documents.text(a.get("actuator"), headers)));
        }
        if (catalogs.isEmpty()) {
            throw new IllegalArgumentException("give at least one of --project, --openapi, --har, --actuator");
        }
        ApiCatalog merged = CatalogMerger.merge(catalogs);
        List<String> excludes = new ArrayList<>(a.all("exclude"));
        if (!a.flag("no-default-excludes")) {
            excludes.addAll(CatalogMerger.DEFAULT_EXCLUDES);
        }
        ApiCatalog filtered = CatalogMerger.filter(merged, a.all("include"), excludes);
        log(filtered.endpoints().size() + " APIs selected (" + merged.endpoints().size() + " discovered)");
        return new Discovery(filtered, settings, projectDir, recordings, basePath);
    }

    /**
     * Makes an OpenAPI catalog's paths relative to the servlet context path, like the source scan's: a server URL
     * of {@code /petclinic/api} with context path {@code /petclinic} prefixes every path with {@code /api}.
     */
    private ApiCatalog relativeToContext(ApiCatalog c, @Nullable String contextPath) {
        String server = c.basePath() == null ? "" : c.basePath();
        String context = contextPath == null ? "" : contextPath;
        if (server.length() > context.length() && server.startsWith(context)
                && (context.isEmpty() || server.charAt(context.length()) == '/')) {
            String prefix = server.substring(context.length());
            log("openapi: server path " + server + " = context path " + (context.isEmpty() ? "/" : context)
                    + " + " + prefix + "; paths rebased onto the context path");
            return CatalogMerger.rebase(c, prefix, contextPath);
        }
        return CatalogMerger.rebase(c, "", contextPath);
    }

    /** The servlet context path: from --base-url, the project's settings, or the OpenAPI server URL. */
    private static @Nullable String basePath(CliArgs a, ProjectSettings settings, List<ApiCatalog> catalogs) {
        String baseUrl = a.get("base-url");
        if (baseUrl != null) {
            String p = java.net.URI.create(baseUrl).getPath();
            return p == null || p.isBlank() || p.equals("/") ? null : p.replaceAll("/+$", "");
        }
        if (settings.contextPath() != null) {
            return settings.contextPath();
        }
        return catalogs.stream().map(ApiCatalog::basePath).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    private int discover(CliArgs a) {
        Discovery d = discoverCatalog(a);
        TableIndex index = new TableIndex(d.catalog().entities(), List.of());
        DataPlan plan = DataPlan.build(d.catalog(), new RealDataBinder(index, Map.of()));
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
        if (!d.recordings().isEmpty()) {
            RecordedTraffic recorded = new RecordedTraffic(d.recordings(), d.catalog(), plan);
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
        Discovery d = discoverCatalog(a);
        ApiCatalog catalog = d.catalog();
        String recordedOrigin = d.recordings().stream().map(HarCapture::origin).filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        String baseUrl = a.get("base-url", recordedOrigin != null
                // the environment the flows were recorded against
                ? recordedOrigin + (d.basePath() == null ? "" : d.basePath())
                : d.settings().contextPath() == null && catalog.basePath() != null
                ? "http://localhost:" + d.settings().serverPort() + catalog.basePath()
                : d.settings().localBaseUrl());
        Path outDir = Path.of(a.get("out", d.project() != null ? d.project().resolve("load-tests").toString()
                : "load-tests"));
        if (!d.recordings().isEmpty() && d.recordings().stream().allMatch(c -> c.observations().isEmpty())) {
            log("har: no successful API call in the recording (journey will be empty)");
        }

        // Existing suite data first: new input is added to it, then everything is verified together.
        Path existingUser = outDir.resolve("data/user.json");
        UserData user = Files.exists(existingUser) ? UserData.load(existingUser) : UserData.empty();
        for (String file : a.all("user-data")) {
            user = user.merge(UserData.load(Path.of(file)));
        }
        for (String v : a.all("value")) {
            int eq = v.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("--value expects key=v1,v2: " + v);
            }
            user = user.merge(new UserData(Map.of(v.substring(0, eq), typedList(v.substring(eq + 1))), Map.of(),
                    Map.of()));
        }
        Map<String, String> bindingText = new LinkedHashMap<>(user.bindings());
        for (String b : a.all("bind")) {
            int eq = b.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("--bind expects key=table.column: " + b);
            }
            bindingText.put(b.substring(0, eq), b.substring(eq + 1));
        }
        user = new UserData(user.fields(), user.payloads(), bindingText);
        Map<String, PoolRef> bindings = new LinkedHashMap<>();
        bindingText.forEach((k, v) -> bindings.put(k, PoolRef.parse(v)));

        DatabaseSampler db = openDatabase(a, d.settings());
        try {
            TableIndex index = new TableIndex(catalog.entities(), db == null ? List.of() : db.tables());
            DataPlan plan = DataPlan.build(catalog, new RealDataBinder(index, bindings));
            tools.jackson.databind.node.ArrayNode journey = null;
            if (!d.recordings().isEmpty()) {
                RecordedTraffic recorded = new RecordedTraffic(d.recordings(), catalog, plan);
                journey = recorded.journey();
                if (!a.flag("har-no-values")) {
                    UserData values = recorded.userData();
                    user = user.merge(values);
                    log("har: recorded values for " + values.fields().size() + " fields and "
                            + values.payloads().size() + " APIs' bodies (sensitive fields never kept)");
                }
                log("har: journey of " + journey.size() + " steps (MODE=journey-<profile>)");
            }
            if (a.flag("interactive")) {
                user = interactive(catalog, plan, user);
            }
            ApiHarvester harvester = a.flag("harvest") ? new ApiHarvester(baseUrl, headers(a), this::log) : null;
            RealDataCollector.Result real = new RealDataCollector(this::log).collect(catalog, plan, index, db,
                    harvester, user, a.integer("sample-size", 200), a.flag("drop-unverified"));
            K6SuiteGenerator.Result r = new K6SuiteGenerator().generate(catalog, plan, real.pools(), real.user(),
                    journey, new K6SuiteGenerator.Options(outDir, baseUrl, a.get("data-mode", "auto"),
                            a.get("auth", "none"), a.get("login-path")));
            out.println("Generated k6 suite in " + r.outDir().toAbsolutePath().normalize());
            out.println("  " + r.apis() + " APIs, " + r.fields() + " fields, " + r.pools() + " real-data pools");
            out.println("  next: cd " + r.outDir() + " && ./run.sh smoke     (or: loadtest run --suite "
                    + r.outDir() + " --mode mixed-load)");
            return 0;
        } finally {
            if (db != null) {
                try {
                    db.close();
                } catch (SQLException e) {
                    log("closing database connection failed: " + e.getMessage());
                }
            }
        }
    }

    private @Nullable DatabaseSampler openDatabase(CliArgs a, ProjectSettings settings) {
        if (a.flag("no-db")) {
            return null;
        }
        String url = a.get("db-url", settings.datasourceUrl());
        if (url == null || url.isBlank()) {
            log("no database configured (--db-url or the project's spring.datasource.url): real data from "
                    + "database disabled");
            return null;
        }
        String user = a.get("db-user", settings.datasourceUsername());
        String password = a.get("db-password", System.getenv().getOrDefault("LOADTEST_DB_PASSWORD",
                settings.datasourcePassword() == null ? "" : settings.datasourcePassword()));
        try {
            DatabaseSampler db = DatabaseSampler.connect(url, user, password, a.get("db-schema"));
            log("database: " + db.tables().size() + " tables at " + url.replaceAll("password=[^&;]*", "password=***"));
            return db;
        } catch (SQLException e) {
            log("database unavailable (" + e.getMessage() + "): real data from database disabled");
            return null;
        }
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
        if (!Files.exists(suite.resolve("main.js"))) {
            throw new IllegalArgumentException("--suite " + suite + " has no main.js (run generate first)");
        }
        String mode = a.get("mode", "smoke");
        if (!LoadMode.modeNames().contains(mode)) {
            throw new IllegalArgumentException("--mode must be one of " + String.join(", ", LoadMode.modeNames()));
        }
        Map<String, String> env = new LinkedHashMap<>();
        Map<String, String> mapping = Map.of("api", "API", "vus", "VUS", "rate", "RATE", "duration-scale",
                "DURATION_SCALE", "base-url", "BASE_URL", "per-api", "PER_API", "preview-count", "PREVIEW_COUNT");
        mapping.forEach((opt, envName) -> {
            if (a.get(opt) != null) {
                env.put(envName, a.get(opt));
            }
        });
        if (a.flag("read-only")) {
            env.put("READ_ONLY", "true");
        }
        return new K6Runner().run(new K6Runner.Run(suite, mode, a.get("data-mode"), env, a.get("k6"),
                a.passThrough()));
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
                  [-- extra k6 args]
                """);
    }
}
