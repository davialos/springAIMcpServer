package com.springaimcpservercommon.loadtest.api;

import com.springaimcpservercommon.loadtest.data.ApiHarvester;
import com.springaimcpservercommon.loadtest.data.DataPlan;
import com.springaimcpservercommon.loadtest.data.DatabaseSampler;
import com.springaimcpservercommon.loadtest.data.DbTable;
import com.springaimcpservercommon.loadtest.data.PoolRef;
import com.springaimcpservercommon.loadtest.data.RealDataBinder;
import com.springaimcpservercommon.loadtest.data.RealDataCollector;
import com.springaimcpservercommon.loadtest.data.RecordedTraffic;
import com.springaimcpservercommon.loadtest.data.SeedPlan;
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
import com.springaimcpservercommon.loadtest.discovery.SqlSchemaReader;
import com.springaimcpservercommon.loadtest.k6.K6SuiteGenerator;
import com.springaimcpservercommon.loadtest.model.ApiCatalog;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ArrayNode;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The load-test generator as a library: discovers a Spring project's APIs, plans the data of every request field
 * from the entity/table relationships, and writes a runnable k6 suite. The CLI ({@code scripts/loadtest.sh}), the
 * Maven plugin, the Gradle script, the JUnit extension and the MCP server are thin layers over this class.
 * <pre>{@code
 * GenerationResult r = LoadTestGenerator.builder()
 *         .project(Path.of("../shop"))
 *         .outDir(Path.of("../shop/load-tests"))
 *         .noDatabase()
 *         .build()
 *         .generate();
 * }</pre>
 * Instances are immutable and may be reused; every call re-reads the project.
 */
public final class LoadTestGenerator {

    private final Settings s;

    private LoadTestGenerator(Settings s) {
        this.s = s;
    }

    /**
     * Starts a builder.
     *
     * @return a builder with the CLI's defaults
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Supplies values interactively (the CLI asks API by API); called once during {@link #generate()}.
     */
    @FunctionalInterface
    public interface UserDataPrompt {

        /**
         * Asks for values.
         *
         * @param catalog the APIs that will be generated
         * @param plan    every field and its kind
         * @param current user data so far
         * @return user data to use (normally {@code current} merged with the answers)
         */
        UserData prompt(ApiCatalog catalog, DataPlan plan, UserData current);
    }

    /**
     * What discovery found, without writing anything.
     *
     * @param catalog     the selected APIs, request schemas and JPA entities
     * @param plan        every request field: kind and real-data binding (no database consulted)
     * @param seed        relationship-ordered seeding through the create endpoints
     * @param settings    the project's Spring settings (port, context path, datasource …)
     * @param project     project directory, if one was given
     * @param recordings  browser recordings read ({@code --har})
     * @param basePath    servlet context path of the target, if known
     * @param discovered  number of APIs before include/exclude filters
     */
    public record DiscoveryResult(ApiCatalog catalog, DataPlan plan, SeedPlan seed, ProjectSettings settings,
                                  @Nullable Path project, List<HarCapture> recordings, @Nullable String basePath,
                                  int discovered) {

        /** Compact constructor: defensive copy. */
        public DiscoveryResult {
            recordings = List.copyOf(recordings);
        }

        /**
         * The recorded browser flow as journey steps, if recordings were read.
         *
         * @return the replayable traffic, or {@code null}
         */
        public @Nullable RecordedTraffic recordedTraffic() {
            return recordings.isEmpty() ? null : new RecordedTraffic(recordings, catalog, plan);
        }
    }

    /**
     * What generation wrote.
     *
     * @param outDir   suite directory
     * @param baseUrl  target base URL written into {@code loadtest.config.json}
     * @param apis     API modules written
     * @param fields   planned request fields
     * @param pools    non-empty real-data pools
     * @param seed     seeding steps written to {@code data/seed.json}
     * @param journey  recorded journey steps written to {@code data/journey.json} (0 without recordings)
     */
    public record GenerationResult(Path outDir, String baseUrl, int apis, int fields, int pools, SeedPlan seed,
                                   int journey) {
    }

    // ── discover ───────────────────────────────────────────────────────────────────────────────────────

    /**
     * Discovers the APIs and plans their data. Reads files and URLs, never a database.
     *
     * @return the discovery
     * @throws IllegalArgumentException when no source is configured or the project is not a directory
     */
    public DiscoveryResult discover() {
        Path projectDir = s.project;
        List<ApiCatalog> catalogs = new ArrayList<>();
        ProjectSettings settings = ProjectSettings.DEFAULTS;
        if (projectDir != null) {
            if (!Files.isDirectory(projectDir)) {
                throw new IllegalArgumentException("project " + projectDir + " is not a directory");
            }
            settings = ProjectSettings.read(projectDir);
        }
        // Precedence: OpenAPI (authoritative contract) > sources (constraints, entities) > browser recordings
        // (observed shapes and values) > actuator (live routes)
        List<String> specs = new ArrayList<>(s.openApi);
        if (specs.isEmpty() && projectDir != null && s.bundledOpenApi) {
            for (Path spec : SpringSourceScanner.bundledOpenApiSpecs(projectDir)) {
                log("openapi: using the project's bundled " + projectDir.relativize(spec));
                specs.add(spec.toString());
            }
        }
        for (String spec : specs) {
            ApiCatalog c = new OpenApiReader(this::log).read(Documents.text(spec, s.headers));
            catalogs.add(projectDir == null ? c : relativeToContext(c, settings.contextPath()));
        }
        if (projectDir != null) {
            catalogs.add(new SpringSourceScanner(this::log).scan(projectDir));
        }
        String basePath = basePath(settings, catalogs);
        List<HarCapture> recordings = new ArrayList<>();
        if (!s.har.isEmpty()) {
            List<String> known = new ArrayList<>();
            catalogs.forEach(c -> c.endpoints().forEach(e -> known.add(e.path())));
            for (String har : s.har) {
                HarCapture capture = new HarReader(this::log).read(Documents.text(har, Map.of()),
                        new HarReader.Options(s.harHosts, basePath, known));
                recordings.add(capture);
                catalogs.add(capture.catalog());
            }
        }
        if (s.actuator != null) {
            catalogs.add(new ActuatorMappingsReader(this::log).read(Documents.text(s.actuator, s.headers)));
        }
        if (catalogs.isEmpty()) {
            throw new IllegalArgumentException("give at least one of project, openApi, har, actuator");
        }
        ApiCatalog merged = CatalogMerger.merge(catalogs);
        List<String> excludes = new ArrayList<>(s.exclude);
        if (s.defaultExcludes) {
            excludes.addAll(CatalogMerger.DEFAULT_EXCLUDES);
        }
        ApiCatalog filtered = CatalogMerger.filter(merged, s.include, excludes);
        log(filtered.endpoints().size() + " APIs selected (" + merged.endpoints().size() + " discovered)");
        TableIndex index = scriptIndex(filtered, projectDir);
        DataPlan plan = DataPlan.build(filtered, new RealDataBinder(index, Map.of()));
        SeedPlan seed = SeedPlan.build(filtered, plan, index, this::log);
        return new DiscoveryResult(filtered, plan, seed, settings, projectDir, recordings, basePath,
                merged.endpoints().size());
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

    /** The servlet context path: from the base URL, the project's settings, or the OpenAPI server URL. */
    private @Nullable String basePath(ProjectSettings settings, List<ApiCatalog> catalogs) {
        if (s.baseUrl != null) {
            String p = URI.create(s.baseUrl).getPath();
            return p == null || p.isBlank() || p.equals("/") ? null : p.replaceAll("/+$", "");
        }
        if (settings.contextPath() != null) {
            return settings.contextPath();
        }
        return catalogs.stream().map(ApiCatalog::basePath).filter(Objects::nonNull).findFirst().orElse(null);
    }

    /** Without a database: the JPA entities plus the tables the project's DDL scripts declare. */
    private TableIndex scriptIndex(ApiCatalog catalog, @Nullable Path project) {
        List<DbTable> tables = project == null ? List.of() : new SqlSchemaReader(this::log).read(project);
        return new TableIndex(catalog.entities(), tables, false);
    }

    // ── generate ───────────────────────────────────────────────────────────────────────────────────────

    /**
     * Discovers, collects real data (database, API harvest, user values) and writes the suite. An existing suite
     * is regenerated: the team's config, user data and hooks are kept.
     *
     * @return what was written
     */
    public GenerationResult generate() {
        DiscoveryResult d = discover();
        ApiCatalog catalog = d.catalog();
        String recordedOrigin = d.recordings().stream().map(HarCapture::origin).filter(Objects::nonNull)
                .findFirst().orElse(null);
        String baseUrl = s.baseUrl != null ? s.baseUrl : recordedOrigin != null
                // the environment the flows were recorded against
                ? recordedOrigin + (d.basePath() == null ? "" : d.basePath())
                : d.settings().contextPath() == null && catalog.basePath() != null
                ? "http://localhost:" + d.settings().serverPort() + catalog.basePath()
                : d.settings().localBaseUrl();
        Path outDir = s.outDir != null ? s.outDir
                : d.project() != null ? d.project().resolve("load-tests") : Path.of("load-tests");
        if (!d.recordings().isEmpty() && d.recordings().stream().allMatch(c -> c.observations().isEmpty())) {
            log("har: no successful API call in the recording (journey will be empty)");
        }

        // Existing suite data first: new input is added to it, then everything is verified together.
        Path existingUser = outDir.resolve("data/user.json");
        UserData user = Files.exists(existingUser) ? UserData.load(existingUser) : UserData.empty();
        for (Path file : s.userDataFiles) {
            user = user.merge(UserData.load(file));
        }
        for (var v : s.values.entrySet()) {
            user = user.merge(new UserData(Map.of(v.getKey(), v.getValue()), Map.of(), Map.of()));
        }
        Map<String, String> bindingText = new LinkedHashMap<>(user.bindings());
        bindingText.putAll(s.bindings);
        user = new UserData(user.fields(), user.payloads(), bindingText);
        Map<String, PoolRef> bindings = new LinkedHashMap<>();
        bindingText.forEach((k, v) -> bindings.put(k, PoolRef.parse(v)));

        DatabaseSampler db = openDatabase(d.settings());
        try {
            TableIndex index = db != null ? new TableIndex(catalog.entities(), db.tables())
                    : scriptIndex(catalog, d.project());
            DataPlan plan = DataPlan.build(catalog, new RealDataBinder(index, bindings));
            SeedPlan seed = SeedPlan.build(catalog, plan, index, this::log);
            if (!seed.steps().isEmpty()) {
                log("seed: " + seed.steps().size() + " tables created through their APIs before the load, in order "
                        + seed.steps().stream().map(SeedPlan.Step::table).toList());
            }
            ArrayNode journey = null;
            if (!d.recordings().isEmpty()) {
                RecordedTraffic recorded = new RecordedTraffic(d.recordings(), catalog, plan);
                journey = recorded.journey();
                if (s.harValues) {
                    UserData values = recorded.userData();
                    user = user.merge(values);
                    log("har: recorded values for " + values.fields().size() + " fields and "
                            + values.payloads().size() + " APIs' bodies (sensitive fields never kept)");
                }
                log("har: journey of " + journey.size() + " steps (MODE=journey-<profile>)");
            }
            if (s.prompt != null) {
                user = s.prompt.prompt(catalog, plan, user);
            }
            ApiHarvester harvester = s.harvest ? new ApiHarvester(baseUrl, s.headers, this::log) : null;
            RealDataCollector.Result real = new RealDataCollector(this::log, seed.pools()).collect(catalog, plan,
                    index, db, harvester, user, s.sampleSize, s.dropUnverified);
            K6SuiteGenerator.Result r = new K6SuiteGenerator().generate(catalog, plan, real.pools(), real.user(),
                    journey, seed, new K6SuiteGenerator.Options(outDir, baseUrl, s.dataMode, s.authType,
                            s.loginPath));
            return new GenerationResult(r.outDir(), baseUrl, r.apis(), r.fields(), r.pools(), seed,
                    journey == null ? 0 : journey.size());
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

    private @Nullable DatabaseSampler openDatabase(ProjectSettings settings) {
        if (!s.useDatabase) {
            return null;
        }
        String url = s.dbUrl != null ? s.dbUrl : settings.datasourceUrl();
        if (url == null || url.isBlank()) {
            log("no database configured (dbUrl or the project's spring.datasource.url): real data from "
                    + "database disabled");
            return null;
        }
        String user = s.dbUser != null ? s.dbUser : settings.datasourceUsername();
        String password = s.dbPassword != null ? s.dbPassword
                : System.getenv().getOrDefault("LOADTEST_DB_PASSWORD",
                settings.datasourcePassword() == null ? "" : settings.datasourcePassword());
        try {
            DatabaseSampler db = DatabaseSampler.connect(url, user, password, s.dbSchema);
            log("database: " + db.tables().size() + " tables at " + url.replaceAll("password=[^&;]*", "password=***"));
            return db;
        } catch (SQLException e) {
            log("database unavailable (" + e.getMessage() + "): real data from database disabled");
            return null;
        }
    }

    private void log(String line) {
        s.log.accept(line);
    }

    // ── configuration ──────────────────────────────────────────────────────────────────────────────────

    /** Immutable snapshot of a builder. */
    private record Settings(@Nullable Path project, List<String> openApi, boolean bundledOpenApi,
                            @Nullable String actuator, List<String> har, List<String> harHosts, boolean harValues,
                            List<String> include, List<String> exclude, boolean defaultExcludes,
                            Map<String, String> headers, @Nullable Path outDir, @Nullable String baseUrl,
                            String dataMode, boolean useDatabase, @Nullable String dbUrl, @Nullable String dbUser,
                            @Nullable String dbPassword, @Nullable String dbSchema, int sampleSize, boolean harvest,
                            List<Path> userDataFiles, Map<String, List<Object>> values, Map<String, String> bindings,
                            boolean dropUnverified, String authType, @Nullable String loginPath,
                            @Nullable UserDataPrompt prompt, Consumer<String> log) {
    }

    /**
     * Configures a {@link LoadTestGenerator}. Every option mirrors a CLI option of the same name.
     */
    public static final class Builder {
        private @Nullable Path project;
        private final List<String> openApi = new ArrayList<>();
        private boolean bundledOpenApi = true;
        private @Nullable String actuator;
        private final List<String> har = new ArrayList<>();
        private final List<String> harHosts = new ArrayList<>();
        private boolean harValues = true;
        private final List<String> include = new ArrayList<>();
        private final List<String> exclude = new ArrayList<>();
        private boolean defaultExcludes = true;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private @Nullable Path outDir;
        private @Nullable String baseUrl;
        private String dataMode = "auto";
        private boolean useDatabase = true;
        private @Nullable String dbUrl;
        private @Nullable String dbUser;
        private @Nullable String dbPassword;
        private @Nullable String dbSchema;
        private int sampleSize = 200;
        private boolean harvest;
        private final List<Path> userDataFiles = new ArrayList<>();
        private final Map<String, List<Object>> values = new LinkedHashMap<>();
        private final Map<String, String> bindings = new LinkedHashMap<>();
        private boolean dropUnverified;
        private String authType = "none";
        private @Nullable String loginPath;
        private @Nullable UserDataPrompt prompt;
        private Consumer<String> log = line -> { };

        private Builder() {
        }

        /**
         * The Spring project: sources, {@code application.yml}, JPA entities, DDL scripts, bundled OpenAPI.
         *
         * @param dir project directory
         * @return this
         */
        public Builder project(Path dir) {
            this.project = dir;
            return this;
        }

        /**
         * An OpenAPI 3 document (URL or file); repeatable. When none is given, specs bundled in the project are
         * used unless {@link #bundledOpenApi(boolean)} is off.
         *
         * @param urlOrFile document location
         * @return this
         */
        public Builder openApi(String urlOrFile) {
            this.openApi.add(urlOrFile);
            return this;
        }

        /**
         * Whether OpenAPI documents bundled in the project are read when no {@link #openApi(String)} is given.
         *
         * @param on default {@code true}
         * @return this
         */
        public Builder bundledOpenApi(boolean on) {
            this.bundledOpenApi = on;
            return this;
        }

        /**
         * {@code /actuator/mappings} of the running app (URL or file).
         *
         * @param urlOrFile location
         * @return this
         */
        public Builder actuator(String urlOrFile) {
            this.actuator = urlOrFile;
            return this;
        }

        /**
         * A browser recording (DevTools ▸ Network ▸ Export HAR); repeatable.
         *
         * @param file HAR file or URL
         * @return this
         */
        public Builder har(String file) {
            this.har.add(file);
            return this;
        }

        /**
         * Keeps recorded calls to this host only (default: the most-called host); repeatable.
         *
         * @param host host name
         * @return this
         */
        public Builder harHost(String host) {
            this.harHosts.add(host);
            return this;
        }

        /**
         * Whether recorded values become user data ({@code --har-no-values} turns this off).
         *
         * @param on default {@code true}
         * @return this
         */
        public Builder harValues(boolean on) {
            this.harValues = on;
            return this;
        }

        /**
         * Keeps only matching APIs ({@code /api/**}, {@code GET /orders/*} or an API id); repeatable.
         *
         * @param pattern pattern
         * @return this
         */
        public Builder include(String pattern) {
            this.include.add(pattern);
            return this;
        }

        /**
         * Drops matching APIs; repeatable.
         *
         * @param pattern pattern
         * @return this
         */
        public Builder exclude(String pattern) {
            this.exclude.add(pattern);
            return this;
        }

        /**
         * Whether {@code /error}, {@code /actuator/**}, API docs and {@code /dynamic-ai/admin/**} are dropped.
         *
         * @param on default {@code true}
         * @return this
         */
        public Builder defaultExcludes(boolean on) {
            this.defaultExcludes = on;
            return this;
        }

        /**
         * A header for URL fetches and harvesting.
         *
         * @param name  header name
         * @param value header value
         * @return this
         */
        public Builder header(String name, String value) {
            this.headers.put(name, value);
            return this;
        }

        /**
         * Suite directory (default {@code <project>/load-tests}).
         *
         * @param dir directory
         * @return this
         */
        public Builder outDir(Path dir) {
            this.outDir = dir;
            return this;
        }

        /**
         * Target base URL with context path (default {@code http://localhost:<server.port><context-path>}).
         *
         * @param url base URL
         * @return this
         */
        public Builder baseUrl(String url) {
            this.baseUrl = url;
            return this;
        }

        /**
         * Default data mode written to the config: {@code auto}, {@code dummy}, {@code random}, {@code real},
         * {@code user} or {@code mixed}.
         *
         * @param mode data mode
         * @return this
         */
        public Builder dataMode(String mode) {
            this.dataMode = mode;
            return this;
        }

        /**
         * Database for real data (default: the project's {@code spring.datasource.*}).
         *
         * @param url      JDBC URL
         * @param user     user, or {@code null} for the project's
         * @param password password, or {@code null} for {@code LOADTEST_DB_PASSWORD} / the project's
         * @return this
         */
        public Builder database(String url, @Nullable String user, @Nullable String password) {
            this.useDatabase = true;
            this.dbUrl = url;
            this.dbUser = user;
            this.dbPassword = password;
            return this;
        }

        /**
         * Narrows sampling to one schema.
         *
         * @param schema schema name
         * @return this
         */
        public Builder databaseSchema(String schema) {
            this.dbSchema = schema;
            return this;
        }

        /**
         * Never connects to a database; tables come from JPA entities and the project's DDL scripts.
         *
         * @return this
         */
        public Builder noDatabase() {
            this.useDatabase = false;
            return this;
        }

        /**
         * Real values sampled per pool.
         *
         * @param n default 200
         * @return this
         */
        public Builder sampleSize(int n) {
            if (n < 1) {
                throw new IllegalArgumentException("sampleSize must be positive");
            }
            this.sampleSize = n;
            return this;
        }

        /**
         * Also fills real-data pools from the running API's collection endpoints.
         *
         * @param on default {@code false}
         * @return this
         */
        public Builder harvest(boolean on) {
            this.harvest = on;
            return this;
        }

        /**
         * User data file: JSON/YAML {@code {fields, payloads, bindings}} or CSV; repeatable.
         *
         * @param file file
         * @return this
         */
        public Builder userData(Path file) {
            this.userDataFiles.add(file);
            return this;
        }

        /**
         * User values for a field key; repeatable (values accumulate).
         *
         * @param key    field key, {@code Owner.field} or a bare field name
         * @param values values
         * @return this
         */
        public Builder value(String key, List<?> values) {
            this.values.computeIfAbsent(key, k -> new ArrayList<>()).addAll(values);
            return this;
        }

        /**
         * Forces a field to draw real values from a column.
         *
         * @param key         field key
         * @param tableColumn {@code [schema.]table.column}
         * @return this
         */
        public Builder bind(String key, String tableColumn) {
            this.bindings.put(key, tableColumn);
            return this;
        }

        /**
         * Drops user values of id/FK fields that are not in the database.
         *
         * @param on default {@code false}
         * @return this
         */
        public Builder dropUnverified(boolean on) {
            this.dropUnverified = on;
            return this;
        }

        /**
         * Authentication written to the config.
         *
         * @param type      {@code none}, {@code bearer}, {@code basic}, {@code apiKey} or {@code login}
         * @param loginPath login path for {@code login}, else {@code null}
         * @return this
         */
        public Builder auth(String type, @Nullable String loginPath) {
            this.authType = type;
            this.loginPath = loginPath;
            return this;
        }

        /**
         * Asks for user values during generation.
         *
         * @param prompt the prompt
         * @return this
         */
        public Builder prompt(UserDataPrompt prompt) {
            this.prompt = prompt;
            return this;
        }

        /**
         * Receives progress lines (what was found, sampled, skipped). Never receives row data or secrets.
         *
         * @param log consumer
         * @return this
         */
        public Builder log(Consumer<String> log) {
            this.log = log;
            return this;
        }

        /**
         * Builds the generator.
         *
         * @return an immutable generator
         */
        public LoadTestGenerator build() {
            Map<String, List<Object>> copiedValues = new LinkedHashMap<>();
            values.forEach((k, v) -> copiedValues.put(k, List.copyOf(v)));
            return new LoadTestGenerator(new Settings(project, List.copyOf(openApi), bundledOpenApi, actuator,
                    List.copyOf(har), List.copyOf(harHosts), harValues, List.copyOf(include), List.copyOf(exclude),
                    defaultExcludes, Map.copyOf(headers), outDir, baseUrl, dataMode, useDatabase, dbUrl, dbUser,
                    dbPassword, dbSchema, sampleSize, harvest, List.copyOf(userDataFiles), Map.copyOf(copiedValues),
                    Map.copyOf(bindings), dropUnverified, authType, loginPath, prompt, log));
        }
    }
}
