package com.springaimcpservercommon.loadtest.k6;

import com.springaimcpservercommon.loadtest.data.DataPlan;
import com.springaimcpservercommon.loadtest.data.FieldPlan;
import com.springaimcpservercommon.loadtest.data.SeedPlan;
import com.springaimcpservercommon.loadtest.data.UserData;
import com.springaimcpservercommon.loadtest.discovery.Documents;
import com.springaimcpservercommon.loadtest.model.ApiCatalog;
import com.springaimcpservercommon.loadtest.model.ApiEndpoint;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Writes a complete, runnable k6 suite for a catalog:
 * <pre>
 * loadtest.config.json   editable settings (kept across regenerations)
 * main.js                entry point: options per MODE, one exec function per API, mixed traffic, preview
 * apis/&lt;id&gt;.js          request builder per API (path, query, headers, body)
 * providers/schemas.js   data provider per request DTO
 * lib/*.js               runtime: data sources, dummy/random generators, modes, HTTP, report
 * data/real.json         real-data pools (database / API harvest)
 * data/user.json         user-supplied values, payloads and bindings
 * data/plan.json         every field: kind and real-data binding
 * data/bindings.md        which inputs carry real data, from which column/query, and which do not
 * data/seed.json         relationship-ordered seeding through the create endpoints (k6 setup)
 * data/journey.json      recorded browser flow (HAR) replayed by MODE=journey-&lt;profile&gt;
 * hooks.js               user hooks (created once, never overwritten)
 * grafana/               Prometheus + Grafana stack and dashboard (written by GrafanaStack)
 * README.md              how to run; API and field tables
 * </pre>
 */
public final class K6SuiteGenerator {

    private static final List<String> RUNTIME_FILES = List.of(
            "lib/data.js", "lib/dummy.js", "lib/random.js", "lib/modes.js", "lib/http.js", "lib/report.js",
            "lib/grafana.js", "lib/dictionaries.json");

    /**
     * Generation options.
     *
     * @param outDir    suite directory
     * @param baseUrl   target base URL (with context path)
     * @param dataMode  default data mode
     * @param authType  none, bearer, basic, apiKey or login
     * @param loginPath login path for {@code login} auth, or {@code null}
     */
    public record Options(Path outDir, String baseUrl, String dataMode, String authType, @Nullable String loginPath) {
    }

    /**
     * What was written.
     *
     * @param outDir suite directory
     * @param apis   number of API modules
     * @param fields number of planned fields
     * @param pools  number of non-empty real-data pools
     */
    public record Result(Path outDir, int apis, int fields, int pools) {
    }

    /**
     * Generates (or regenerates) a suite.
     *
     * @param catalog discovered APIs
     * @param plan    data plan
     * @param pools   real-data pools collected now (merged over the ones on disk)
     * @param user    the complete user data for {@code data/user.json} (callers merge the existing file first, so
     *                that values dropped by verification stay dropped)
     * @param o       options
     * @return summary
     */
    public Result generate(ApiCatalog catalog, DataPlan plan, Map<String, List<Object>> pools, UserData user,
                           Options o) {
        return generate(catalog, plan, pools, user, null, o);
    }

    /**
     * Generates (or regenerates) a suite, with a recorded journey.
     *
     * @param catalog discovered APIs
     * @param plan    data plan
     * @param pools   real-data pools collected now (merged over the ones on disk)
     * @param user    the complete user data for {@code data/user.json}
     * @param journey recorded steps for {@code data/journey.json}, or {@code null} to keep the file on disk
     * @param o       options
     * @return summary
     */
    public Result generate(ApiCatalog catalog, DataPlan plan, Map<String, List<Object>> pools, UserData user,
                           @Nullable ArrayNode journey, Options o) {
        return generate(catalog, plan, pools, user, journey, null, o);
    }

    /**
     * Generates (or regenerates) a suite, with a recorded journey and a seeding plan.
     *
     * @param catalog discovered APIs
     * @param plan    data plan
     * @param pools   real-data pools collected now (merged over the ones on disk)
     * @param user    the complete user data for {@code data/user.json}
     * @param journey recorded steps for {@code data/journey.json}, or {@code null} to keep the file on disk
     * @param seed    relationship-ordered seeding for {@code data/seed.json}, or {@code null} for none
     * @param o       options
     * @return summary
     */
    public Result generate(ApiCatalog catalog, DataPlan plan, Map<String, List<Object>> pools, UserData user,
                           @Nullable ArrayNode journey, @Nullable SeedPlan seed, Options o) {
        Path out = o.outDir();
        try {
            for (String dir : List.of("lib", "apis", "providers", "data", "reports")) {
                Files.createDirectories(out.resolve(dir));
            }
            for (String f : RUNTIME_FILES) {
                copyResource(f, out.resolve(f), true);
            }
            copyResource("hooks.js", out.resolve("hooks.js"), false);
            touch(out.resolve("reports/.gitkeep"));

            JsEmitter js = new JsEmitter(catalog, plan);
            removeStaleGenerated(out.resolve("apis"), catalog);
            for (ApiEndpoint e : catalog.endpoints()) {
                Files.writeString(out.resolve("apis/" + e.id() + ".js"), js.apiModule(e));
            }
            Files.writeString(out.resolve("providers/schemas.js"), js.schemasModule());
            Files.writeString(out.resolve("main.js"), js.mainModule(catalog.endpoints()));

            Path configFile = out.resolve("loadtest.config.json");
            ObjectNode config = SuiteConfig.merge(
                    SuiteConfig.defaults(catalog, new SuiteConfig.Settings(o.baseUrl(), o.dataMode(), o.authType(),
                            o.loginPath())),
                    readIfExists(configFile));
            writeJson(configFile, config);

            Map<String, List<Object>> realPools = mergedPools(out.resolve("data/real.json"), pools, plan);
            writeJson(out.resolve("data/real.json"), Documents.json().valueToTree(realPools));

            writeJson(out.resolve("data/user.json"), user.toJson());
            writeJson(out.resolve("data/plan.json"), planJson(plan, realPools));
            Set<String> seededPools = seed == null ? Set.of() : seed.pools();
            Files.writeString(out.resolve("data/bindings.md"), BindingReport.render(plan, realPools, seededPools));
            writeJson(out.resolve("data/seed.json"), seed == null ? Documents.json().createArrayNode()
                    : seed.toJson());
            Path journeyFile = out.resolve("data/journey.json");
            if (journey != null) {
                writeJson(journeyFile, journey);
            } else if (!Files.exists(journeyFile)) {
                writeJson(journeyFile, Documents.json().createArrayNode());
            }

            Files.writeString(out.resolve("README.md"),
                    SuiteReadme.render(catalog, plan, realPools, user, config, seed));
            Files.writeString(out.resolve("run.sh"), runScript());
            out.resolve("run.sh").toFile().setExecutable(true);
            return new Result(out, catalog.endpoints().size(), plan.fields().size(), realPools.size());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write suite to " + out, e);
        }
    }

    private static void removeStaleGenerated(Path apis, ApiCatalog catalog) throws IOException {
        Set<String> current = new java.util.HashSet<>();
        catalog.endpoints().forEach(e -> current.add(e.id() + ".js"));
        try (Stream<Path> files = Files.list(apis)) {
            for (Path f : files.toList()) {
                String name = f.getFileName().toString();
                if (name.endsWith(".js") && !current.contains(name)
                        && Files.readString(f).startsWith(JsEmitter.GENERATED.substring(0, 20))) {
                    Files.delete(f);
                }
            }
        }
    }

    private static Map<String, List<Object>> mergedPools(Path file, Map<String, List<Object>> fresh, DataPlan plan) {
        Map<String, List<Object>> out = new LinkedHashMap<>();
        JsonNode old = readIfExists(file);
        Set<String> wanted = new java.util.HashSet<>();
        plan.pools().forEach(p -> wanted.add(p.key()));
        if (old != null) {
            for (var e : old.properties()) {
                if (wanted.contains(e.getKey()) && !fresh.containsKey(e.getKey())) {
                    List<Object> values = new ArrayList<>();
                    e.getValue().forEach(v -> values.add(v.isIntegralNumber() ? (Object) v.asLong()
                            : v.isNumber() ? v.decimalValue() : v.isBoolean() ? (Object) v.asBoolean() : v.asString()));
                    out.put(e.getKey(), values);
                }
            }
        }
        out.putAll(fresh);
        return out;
    }

    private static ObjectNode planJson(DataPlan plan, Map<String, List<Object>> pools) {
        ObjectNode root = Documents.json().createObjectNode();
        ArrayNode fields = root.putArray("fields");
        for (FieldPlan f : plan.fields().values()) {
            ObjectNode n = fields.addObject();
            n.put("key", f.key());
            n.put("kind", f.kind().generator());
            if (f.pool() != null) {
                n.put("real", f.pool().key());
                n.put("realValues", pools.getOrDefault(f.pool().key(), List.of()).size());
            }
            if (f.sensitive()) {
                n.put("sensitive", true);
            }
        }
        return root;
    }

    private static @Nullable JsonNode readIfExists(Path file) {
        try {
            return Files.exists(file) ? Documents.parse(Files.readString(file)) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void writeJson(Path file, JsonNode node) throws IOException {
        Files.writeString(file, Documents.json().writerWithDefaultPrettyPrinter().writeValueAsString(node) + "\n");
    }

    private static void touch(Path file) throws IOException {
        if (!Files.exists(file)) {
            Files.writeString(file, "");
        }
    }

    private static void copyResource(String name, Path target, boolean overwrite) throws IOException {
        if (!overwrite && Files.exists(target)) {
            return;
        }
        try (InputStream in = K6SuiteGenerator.class.getResourceAsStream("/loadtest/k6/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing resource /loadtest/k6/" + name);
            }
            Files.write(target, in.readAllBytes());
        }
    }

    private static String runScript() {
        return """
                #!/usr/bin/env bash
                # Runs this suite. Usage: ./run.sh <mode> [data-mode] [extra k6 args]
                #   ./run.sh smoke                     every API on its own, auto data
                #   ./run.sh mixed-spike mixed         weighted mix of all APIs, spike profile, mixed data sources
                #   API=getUser ./run.sh stress dummy  one API, stress profile, dummy data
                #   ./run.sh preview random            print generated requests, send nothing
                #   ./run.sh journey-load              replay the recorded browser flow (generate --har) under load
                # Env (k6 reads it directly): BASE_URL, VUS, RATE, DURATION_SCALE, API, PER_API, READ_ONLY,
                #   AUTH_TOKEN, AUTH_USER, AUTH_PASSWORD, API_KEY, ALLOW_PROD, PREVIEW_COUNT, SEED, SEED_PER_TABLE,
                #   SEED_CLEANUP
                # Grafana: docker compose -f grafana/docker-compose.yml up -d, then GRAFANA=1 ./run.sh mixed-load
                #   (K6_PROMETHEUS_RW_SERVER_URL, GRAFANA_URL, GRAFANA_TOKEN, TEST_ID override the local defaults)
                set -euo pipefail
                cd "$(dirname "$0")"
                mode="${1:-smoke}"
                data="${2:-${DATA_MODE:-}}"
                [ $# -gt 0 ] && shift
                [ $# -gt 0 ] && shift
                args=(run -e "MODE=$mode")
                if [ -n "$data" ]; then args+=(-e "DATA_MODE=$data"); fi
                case "$mode" in *preview) args+=(--log-format=raw --quiet) ;; esac
                case "${GRAFANA:-}" in
                  1|true|yes)
                    export K6_PROMETHEUS_RW_SERVER_URL="${K6_PROMETHEUS_RW_SERVER_URL:-http://localhost:9090/api/v1/write}"
                    export K6_PROMETHEUS_RW_TREND_STATS="${K6_PROMETHEUS_RW_TREND_STATS:-p(95),p(99),avg,max}"
                    export GRAFANA_URL="${GRAFANA_URL:-http://localhost:3000}"
                    export TEST_ID="${TEST_ID:-$mode-$(date -u +%Y%m%dT%H%M%SZ)}"
                    args+=(--out experimental-prometheus-rw --tag "testid=$TEST_ID")
                    echo "grafana: streaming to $K6_PROMETHEUS_RW_SERVER_URL, test run $TEST_ID ($GRAFANA_URL)" >&2
                    ;;
                esac
                exec "${K6:-k6}" "${args[@]}" "$@" main.js
                """;
    }
}
