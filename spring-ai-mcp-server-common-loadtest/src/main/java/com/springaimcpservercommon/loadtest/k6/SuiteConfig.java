package com.springaimcpservercommon.loadtest.k6;

import com.springaimcpservercommon.loadtest.discovery.Documents;
import com.springaimcpservercommon.loadtest.model.ApiCatalog;
import com.springaimcpservercommon.loadtest.model.ApiEndpoint;
import com.springaimcpservercommon.loadtest.model.HttpMethod;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * The suite's editable {@code loadtest.config.json}: target, auth, data modes, thresholds, load profiles and
 * per-API settings. Regeneration keeps every value the team changed and only adds what is new (new APIs, new
 * profiles) and drops APIs that no longer exist.
 */
final class SuiteConfig {

    /** Keys the generator owns: refreshed on every generation. */
    private static final Set<String> GENERATOR_OWNED = Set.of("project", "generatedAt", "_readme");

    private SuiteConfig() {
    }

    /**
     * Generation inputs that end up in the config.
     *
     * @param baseUrl  target base URL (with context path)
     * @param dataMode default data mode
     * @param authType none, bearer, basic, apiKey or login
     * @param loginPath login endpoint path for {@code login} auth
     */
    record Settings(String baseUrl, String dataMode, String authType, @Nullable String loginPath) {
    }

    static ObjectNode defaults(ApiCatalog catalog, Settings s) {
        ObjectNode root = Documents.json().createObjectNode();
        root.put("_readme", "Edit freely: regeneration keeps your values. Secrets never go here — use env vars "
                + "AUTH_TOKEN, AUTH_USER, AUTH_PASSWORD, API_KEY. See README.md.");
        root.put("project", catalog.project());
        root.put("generatedAt", Instant.now().toString());
        root.put("baseUrl", s.baseUrl());
        root.putObject("headers").put("Accept", "application/json");
        root.putObject("http").put("timeout", "30s").put("insecureSkipTLSVerify", false);
        root.putObject("thinkTime").put("min", 0.2).put("max", 1.0);

        ObjectNode auth = root.putObject("auth");
        auth.put("type", s.authType());
        auth.put("header", "Authorization");
        auth.put("apiKeyHeader", "X-API-Key");
        ObjectNode login = auth.putObject("login");
        login.put("method", "POST");
        login.put("path", s.loginPath() != null ? s.loginPath() : "/api/auth/login");
        login.putObject("body").put("username", "${AUTH_USER}").put("password", "${AUTH_PASSWORD}");
        login.put("tokenPath", "token");

        ObjectNode data = root.putObject("data");
        data.put("mode", s.dataMode());
        data.putObject("mix").put("user", 20).put("real", 40).put("dummy", 30).put("random", 10);
        data.put("realIdentifiersInAllModes", true);
        data.put("optionalFieldRate", 0.7);
        data.put("maxArrayItems", 3);
        data.put("maxDepth", 4);
        // real values: uniform | zipf (rank r has weight 1/r^s) | hot (hotFraction of rows take hotShare of requests);
        // SKEW / SKEW_S override. partition "vu": a VU's writes use its own slice of each pool (PARTITION=vu).
        data.putObject("skew").put("mode", "uniform").put("s", 1.1).put("hotFraction", 0.05).put("hotShare", 0.8);
        data.putObject("partition").put("mode", "none").put("slots", 64);
        data.putArray("acceptClientErrorsIn").add("random");
        data.putArray("clientErrorStatuses").add(400).add(404).add(409).add(422);

        ObjectNode safety = root.putObject("safety");
        safety.put("readOnly", false);
        safety.put("blockedHostPattern", "(^|[.-])prod(uction)?([.-]|$)");

        ObjectNode thresholds = root.putObject("thresholds");
        thresholds.putArray("http_req_failed").add("rate<0.01");
        thresholds.putArray("http_req_duration").add("p(95)<800").add("p(99)<2000");
        thresholds.putArray("checks").add("rate>0.95");
        root.putObject("defaults").put("p95Ms", 800).put("maxErrorRate", 0.01);
        root.putObject("perApi").put("schedule", "sequential").put("gap", "5s");
        root.putObject("journey").put("pauseScale", 1.0).put("maxPauseMs", 5000);
        root.putObject("seed").put("enabled", true).put("perTable", 5).put("cleanup", false);
        root.putObject("lifecycle").put("deleteOwnRows", true);
        // responses: check (a mismatch fails the run) | log (counted and logged) | off. sample: share of responses
        // validated (parsing costs load-generator CPU). maxViolations: tolerated mismatches before the threshold fails.
        // readAfterWrite: lifecycle flows compare what a read returns with what the previous write sent.
        ObjectNode validation = root.putObject("validation");
        validation.put("responses", "check").put("sample", 0.25).put("maxViolations", 0);
        validation.putObject("readAfterWrite").put("enabled", true).put("maxMismatches", 0);

        ObjectNode modes = root.putObject("modes");
        for (LoadMode m : LoadMode.values()) {
            modes.set(m.id(), m.defaultProfile());
        }
        ObjectNode apis = root.putObject("apis");
        for (ApiEndpoint e : catalog.endpoints()) {
            ObjectNode a = apis.putObject(e.id());
            a.put("method", e.method().name());
            a.put("path", e.path());
            a.put("enabled", e.method() != HttpMethod.DELETE && e.method() != HttpMethod.OPTIONS);
            a.put("weight", weight(e.method()));
            ArrayNode statuses = a.putArray("expectedStatuses");
            expected(e.method()).forEach(statuses::add);
        }
        return root;
    }

    /** Reads dominate real traffic; deletes are off by default (destructive on shared data). */
    private static int weight(HttpMethod m) {
        return switch (m) {
            case GET -> 6;
            case POST -> 2;
            case PUT, PATCH, HEAD -> 1;
            case DELETE, OPTIONS -> 0;
        };
    }

    private static List<Integer> expected(HttpMethod m) {
        return switch (m) {
            case GET, HEAD -> List.of(200);
            case POST -> List.of(200, 201, 202);
            case PUT -> List.of(200, 201, 204);
            case PATCH, OPTIONS -> List.of(200, 204);
            case DELETE -> List.of(200, 202, 204);
        };
    }

    /**
     * Merges a freshly generated config with the one on disk.
     *
     * @param generated new defaults
     * @param existing  config on disk, or {@code null}
     * @return merged config
     */
    static ObjectNode merge(ObjectNode generated, @Nullable JsonNode existing) {
        if (!(existing instanceof ObjectNode old)) {
            return generated;
        }
        ObjectNode out = generated.deepCopy();
        for (var e : old.properties()) {
            String key = e.getKey();
            if (GENERATOR_OWNED.contains(key)) {
                continue;
            }
            if (key.equals("apis")) {
                ObjectNode apis = (ObjectNode) out.path("apis");
                for (var api : apis.properties()) {
                    JsonNode previous = e.getValue().path(api.getKey());
                    if (previous instanceof ObjectNode p) {
                        ObjectNode merged = deepMerge((ObjectNode) api.getValue(), p);
                        merged.set("method", api.getValue().path("method"));
                        merged.set("path", api.getValue().path("path"));
                        apis.set(api.getKey(), merged);
                    }
                }
                continue;
            }
            JsonNode current = out.get(key);
            if (current instanceof ObjectNode c && e.getValue() instanceof ObjectNode p) {
                out.set(key, deepMerge(c, p));
            } else {
                out.set(key, e.getValue());
            }
        }
        return out;
    }

    private static ObjectNode deepMerge(ObjectNode base, ObjectNode overlay) {
        ObjectNode out = base.deepCopy();
        for (var e : overlay.properties()) {
            JsonNode current = out.get(e.getKey());
            if (current instanceof ObjectNode c && e.getValue() instanceof ObjectNode p) {
                out.set(e.getKey(), deepMerge(c, p));
            } else {
                out.set(e.getKey(), e.getValue());
            }
        }
        return out;
    }
}
