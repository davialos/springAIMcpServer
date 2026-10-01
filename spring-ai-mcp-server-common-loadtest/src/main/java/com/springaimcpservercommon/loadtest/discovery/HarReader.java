package com.springaimcpservercommon.loadtest.discovery;

import com.springaimcpservercommon.loadtest.model.ApiCatalog;
import com.springaimcpservercommon.loadtest.model.ApiEndpoint;
import com.springaimcpservercommon.loadtest.model.ApiParam;
import com.springaimcpservercommon.loadtest.model.HttpMethod;
import com.springaimcpservercommon.loadtest.model.Names;
import com.springaimcpservercommon.loadtest.model.ParamLocation;
import com.springaimcpservercommon.loadtest.model.ScalarSchema;
import com.springaimcpservercommon.loadtest.model.Schema;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a HAR file — what Chrome/Edge DevTools write with Network ▸ "Export HAR" (also Firefox, Safari, Charles,
 * Fiddler, Proxyman) — and turns the recorded API calls into operations and observations.
 * <ul>
 *   <li>Keeps API calls only: {@code fetch}/{@code xhr} resource types (or JSON request/response when the
 *       exporter does not record types); drops documents, scripts, styles, images, fonts, CORS preflights and
 *       non-HTTP schemes; keeps the most-called host unless hosts are given.</li>
 *   <li>Turns concrete URLs into templates: a known template from the project's sources/OpenAPI first, then
 *       id-like segments (numbers, UUIDs, long hex/opaque ids), then segments with digits that vary between
 *       otherwise identical URLs. Variables are named after the collection before them
 *       ({@code /orders/9001} → {@code /orders/{orderId}}).</li>
 *   <li>Never keeps credentials: cookies, {@code Authorization}, CSRF/XSRF, API-key and tracing headers are
 *       dropped; only custom {@code X-…} headers survive. Sensitive body fields are flagged, so their recorded
 *       values are never reused.</li>
 *   <li>Infers parameter and body schemas from the values sent ({@link SchemaInference}).</li>
 * </ul>
 */
public final class HarReader {

    private static final Pattern ID_SEGMENT = Pattern.compile(
            "^(\\d+|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|[0-9a-fA-F]{16,})$");
    private static final Pattern OPAQUE_ID = Pattern.compile("^(?=.*\\d)(?=.*[A-Za-z])[A-Za-z0-9_-]{16,}$");
    private static final Pattern VERSION = Pattern.compile("^v\\d+(\\.\\d+)*$");
    private static final Pattern STATIC_FILE = Pattern.compile(
            "(?i).*\\.(js|mjs|css|map|png|jpe?g|gif|svg|ico|webp|avif|woff2?|ttf|eot|otf|html?|txt|mp4|webm|mp3|wasm)$");
    private static final Set<String> API_TYPES = Set.of("xhr", "fetch");
    private static final Set<String> DROPPED_HEADERS = Set.of("x-csrf-token", "x-xsrf-token", "x-requested-with",
            "x-client-data", "x-forwarded-for", "x-real-ip");
    private static final int MAX_BODY_CHARS = 1_000_000;

    /**
     * What to read.
     *
     * @param hosts          hosts to keep (empty: the host with the most API calls)
     * @param basePath       path prefix to strip (servlet context path), or {@code null}
     * @param knownTemplates path templates already known from sources/OpenAPI, relative to the base path
     */
    public record Options(List<String> hosts, @Nullable String basePath, List<String> knownTemplates) {

        /** Compact constructor: defensive copies. */
        public Options {
            hosts = List.copyOf(hosts);
            knownTemplates = List.copyOf(knownTemplates);
        }
    }

    private final Consumer<String> log;

    /**
     * Creates a reader.
     *
     * @param log receives notes about what was skipped
     */
    public HarReader(Consumer<String> log) {
        this.log = log;
    }

    private record Raw(HttpMethod method, URI uri, List<String> segments, Map<String, List<String>> query,
                       Map<String, String> headers, @Nullable JsonNode body, int status, long startedAtMs,
                       @Nullable JsonNode response) {
    }

    /**
     * Parses a HAR document.
     *
     * @param har HAR JSON text
     * @param o   options
     * @return the operations and observations
     */
    public HarCapture read(String har, Options o) {
        JsonNode entries = Documents.parse(har).path("log").path("entries");
        if (!entries.isArray()) {
            throw new IllegalArgumentException("not a HAR file (no log.entries)");
        }
        List<Raw> api = new ArrayList<>();
        Map<String, Integer> hostCounts = new LinkedHashMap<>();
        int skippedStatic = 0;
        Set<String> skippedBodies = new HashSet<>();
        for (JsonNode entry : entries) {
            JsonNode req = entry.path("request");
            String url = req.path("url").asString("");
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                continue;
            }
            HttpMethod method;
            try {
                method = HttpMethod.parse(req.path("method").asString("GET"));
            } catch (IllegalArgumentException e) {
                continue;
            }
            URI uri = URI.create(url.replace(" ", "%20").replace("|", "%7C"));
            if (method == HttpMethod.OPTIONS || !isApiCall(entry, uri)) {
                skippedStatic++;
                continue;
            }
            JsonNode body = null;
            String text = req.path("postData").path("text").asString("");
            if (!text.isBlank()) {
                body = json(text, req.path("postData").path("mimeType").asString(""));
                if (body == null) {
                    skippedBodies.add(method + " " + uri.getPath());
                    continue; // form/multipart/binary bodies are not generated (same rule as discovery)
                }
            }
            String host = origin(uri);
            hostCounts.merge(host, 1, Integer::sum);
            api.add(new Raw(method, uri, segments(uri, o.basePath()), query(uri), headers(req.path("headers")), body,
                    entry.path("response").path("status").asInt(0), started(entry), response(entry)));
        }
        skippedBodies.forEach(b -> log.accept("har: skipped " + b + " (body is not JSON)"));

        Set<String> keep = new HashSet<>();
        if (!o.hosts().isEmpty()) {
            for (String h : o.hosts()) {
                for (String origin : hostCounts.keySet()) {
                    if (origin.equalsIgnoreCase(h) || URI.create(origin).getHost().equalsIgnoreCase(h)) {
                        keep.add(origin);
                    }
                }
            }
        } else {
            hostCounts.entrySet().stream().max(Map.Entry.comparingByValue()).ifPresent(e -> keep.add(e.getKey()));
        }
        for (Map.Entry<String, Integer> h : hostCounts.entrySet()) {
            if (!keep.contains(h.getKey())) {
                log.accept("har: ignored " + h.getValue() + " calls to " + h.getKey() + " (use --har-host to include)");
            }
        }
        List<Raw> kept = api.stream().filter(r -> keep.contains(origin(r.uri())))
                .sorted(Comparator.comparingLong(Raw::startedAtMs)).toList();

        Map<Raw, String> templates = templates(kept, o.knownTemplates());
        Map<String, List<Raw>> byRoute = new LinkedHashMap<>();
        Map<String, String> templateOf = new HashMap<>();
        for (Raw r : kept) {
            String key = ApiEndpoint.routeKey(r.method(), templates.get(r));
            byRoute.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
            templateOf.putIfAbsent(key, templates.get(r));
        }
        List<ApiEndpoint> endpoints = new ArrayList<>();
        for (Map.Entry<String, List<Raw>> e : byRoute.entrySet()) {
            endpoints.add(endpoint(templateOf.get(e.getKey()), e.getValue()));
        }
        List<HarCapture.Observation> observations = new ArrayList<>();
        for (Raw r : kept) {
            if (r.status() >= 200 && r.status() < 400) {
                String template = templates.get(r);
                observations.add(new HarCapture.Observation(r.method(), template, varValues(template, r.segments()),
                        r.query(), r.headers(), r.body(), r.status(), r.startedAtMs(), r.response()));
            }
        }
        log.accept("har: " + kept.size() + " API calls, " + endpoints.size() + " operations ("
                + skippedStatic + " non-API requests skipped)");
        String origin = keep.size() == 1 ? keep.iterator().next() : null;
        return new HarCapture(new ApiCatalog("recording", o.basePath(), endpoints, Map.of(), List.of()),
                observations, origin);
    }

    // ── filtering and parsing ──────────────────────────────────────────────────────────────────────────

    private static boolean isApiCall(JsonNode entry, URI uri) {
        if (STATIC_FILE.matcher(uri.getPath() == null ? "" : uri.getPath()).matches()) {
            return false;
        }
        String type = entry.path("_resourceType").asString("").toLowerCase(Locale.ROOT);
        if (!type.isEmpty()) {
            return API_TYPES.contains(type);
        }
        String requestMime = entry.path("request").path("postData").path("mimeType").asString("");
        String responseMime = entry.path("response").path("content").path("mimeType").asString("");
        return requestMime.contains("json") || responseMime.contains("json");
    }

    static String origin(URI uri) {
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
    }

    private static List<String> segments(URI uri, @Nullable String basePath) {
        String path = uri.getRawPath() == null ? "/" : uri.getRawPath();
        if (basePath != null && !basePath.isBlank() && (path.equals(basePath) || path.startsWith(basePath + "/"))) {
            path = path.substring(basePath.length());
        }
        List<String> out = new ArrayList<>();
        for (String s : path.split("/")) {
            if (!s.isEmpty()) {
                out.add(decode(s));
            }
        }
        return out;
    }

    private static Map<String, List<String>> query(URI uri) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            out.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return out;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    /** Custom headers only, minus anything that carries credentials or per-session state. */
    private static Map<String, String> headers(JsonNode headers) {
        Map<String, String> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (JsonNode h : headers) {
            String name = h.path("name").asString("");
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("x-") || DROPPED_HEADERS.contains(lower) || lower.startsWith("x-amz-")
                    || lower.startsWith("x-goog-") || lower.startsWith("x-b3-") || lower.contains("auth")
                    || lower.contains("session") || lower.contains("cookie") || Names.isSensitive(name)) {
                continue;
            }
            out.put(name, h.path("value").asString(""));
        }
        return new LinkedHashMap<>(out);
    }

    private static @Nullable JsonNode json(String text, String mime) {
        if (text.length() > MAX_BODY_CHARS) {
            return null;
        }
        String t = text.stripLeading();
        if (!mime.contains("json") && !t.startsWith("{") && !t.startsWith("[")) {
            return null;
        }
        try {
            return Documents.json().readTree(t);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static @Nullable JsonNode response(JsonNode entry) {
        JsonNode content = entry.path("response").path("content");
        String text = content.path("text").asString("");
        if (text.isEmpty()) {
            return null;
        }
        if ("base64".equalsIgnoreCase(content.path("encoding").asString(""))) {
            try {
                text = new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return json(text, content.path("mimeType").asString(""));
    }

    private static long started(JsonNode entry) {
        try {
            return OffsetDateTime.parse(entry.path("startedDateTime").asString("")).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // ── templating ─────────────────────────────────────────────────────────────────────────────────────

    private Map<Raw, String> templates(List<Raw> calls, List<String> known) {
        List<Pattern> knownPatterns = new ArrayList<>();
        List<String> sortedKnown = known.stream()
                .sorted(Comparator.comparingInt((String t) -> t.split("\\{").length).thenComparing(t -> t)).toList();
        for (String t : sortedKnown) {
            knownPatterns.add(templatePattern(t));
        }
        Map<Raw, String> out = new LinkedHashMap<>();
        Map<Raw, boolean[]> vars = new LinkedHashMap<>();
        for (Raw r : calls) {
            String path = "/" + String.join("/", r.segments());
            String match = null;
            for (int i = 0; i < knownPatterns.size(); i++) {
                if (knownPatterns.get(i).matcher(path).matches()) {
                    match = sortedKnown.get(i);
                    break;
                }
            }
            if (match != null) {
                out.put(r, match);
                continue;
            }
            boolean[] v = new boolean[r.segments().size()];
            for (int i = 0; i < v.length; i++) {
                String s = r.segments().get(i);
                v[i] = !VERSION.matcher(s).matches() && (ID_SEGMENT.matcher(s).matches() || OPAQUE_ID.matcher(s).matches());
            }
            vars.put(r, v);
        }
        varyingSegments(vars);
        for (Map.Entry<Raw, boolean[]> e : vars.entrySet()) {
            out.put(e.getKey(), template(e.getKey().segments(), e.getValue()));
        }
        return out;
    }

    /** {@code /orders/{id}/lines} → {@code ^/orders/[^/]+/lines/?$}. */
    static Pattern templatePattern(String template) {
        StringBuilder re = new StringBuilder("^");
        for (String seg : template.split("/")) {
            if (seg.isEmpty()) {
                continue;
            }
            re.append('/').append(TEMPLATE_VAR.matcher(seg).matches() ? "[^/]+" : Pattern.quote(seg));
        }
        return Pattern.compile((re.length() == 1 ? "^/" : re.toString()) + "/?$");
    }

    /**
     * Segments containing a digit that differ between calls which are otherwise identical
     * ({@code /products/SKU-7}, {@code /products/SKU-12}) are variables too.
     */
    private static void varyingSegments(Map<Raw, boolean[]> vars) {
        boolean changed = true;
        while (changed) {
            changed = false;
            Map<String, Set<String>> distinct = new HashMap<>();
            Map<String, List<Map.Entry<Raw, boolean[]>>> members = new HashMap<>();
            for (Map.Entry<Raw, boolean[]> e : vars.entrySet()) {
                List<String> segs = e.getKey().segments();
                for (int p = 0; p < segs.size(); p++) {
                    String s = segs.get(p);
                    if (e.getValue()[p] || !s.matches(".*\\d.*") || VERSION.matcher(s).matches()) {
                        continue;
                    }
                    StringBuilder key = new StringBuilder(e.getKey().method() + " " + p);
                    for (int q = 0; q < segs.size(); q++) {
                        key.append('/').append(q == p ? "*" : e.getValue()[q] ? "{}" : segs.get(q));
                    }
                    distinct.computeIfAbsent(key.toString(), k -> new HashSet<>()).add(s);
                    members.computeIfAbsent(key.toString(), k -> new ArrayList<>()).add(e);
                }
            }
            for (Map.Entry<String, Set<String>> d : distinct.entrySet()) {
                if (d.getValue().size() >= 2) {
                    int p = Integer.parseInt(d.getKey().substring(d.getKey().indexOf(' ') + 1, d.getKey().indexOf('/')));
                    for (Map.Entry<Raw, boolean[]> m : members.get(d.getKey())) {
                        if (!m.getValue()[p]) {
                            m.getValue()[p] = true;
                            changed = true;
                        }
                    }
                }
            }
        }
    }

    private static String template(List<String> segments, boolean[] vars) {
        StringBuilder out = new StringBuilder();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < segments.size(); i++) {
            out.append('/');
            if (!vars[i]) {
                out.append(segments.get(i));
                continue;
            }
            String prev = i > 0 && !vars[i - 1] ? segments.get(i - 1) : null;
            String base = prev == null || VERSION.matcher(prev).matches() ? "id"
                    : Names.jsIdentifier(Names.singular(prev.toLowerCase(Locale.ROOT))) + "Id";
            String name = base;
            for (int n = 2; !used.add(name); n++) {
                name = base + n;
            }
            out.append('{').append(name).append('}');
        }
        return out.isEmpty() ? "/" : out.toString();
    }

    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\{([^}]+)}");

    /**
     * Concrete values of a template's variables in a concrete path.
     *
     * @param template template
     * @param segments concrete segments
     * @return values in template order
     */
    static List<String> varValues(String template, List<String> segments) {
        List<String> out = new ArrayList<>();
        String[] parts = template.split("/");
        int seg = 0;
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (TEMPLATE_VAR.matcher(part).matches() && seg < segments.size()) {
                out.add(segments.get(seg));
            }
            seg++;
        }
        return out;
    }

    // ── operations ─────────────────────────────────────────────────────────────────────────────────────

    private static ApiEndpoint endpoint(String template, List<Raw> calls) {
        Raw first = calls.getFirst();
        List<ApiParam> params = new ArrayList<>();
        Matcher m = TEMPLATE_VAR.matcher(template);
        int index = 0;
        while (m.find()) {
            Schema s = null;
            for (Raw r : calls) {
                List<String> values = varValues(template, r.segments());
                if (index < values.size()) {
                    s = SchemaInference.merge(s, SchemaInference.inferText(values.get(index)));
                }
            }
            params.add(new ApiParam(m.group(1), ParamLocation.PATH, true, s != null ? s : ScalarSchema.of(
                    com.springaimcpservercommon.loadtest.model.ScalarType.STRING, null), null));
            index++;
        }
        addParams(params, calls, ParamLocation.QUERY);
        addParams(params, calls, ParamLocation.HEADER);
        List<Raw> ok = calls.stream().filter(r -> r.status() >= 200 && r.status() < 400).toList();
        Schema body = null;
        for (Raw r : ok.isEmpty() ? calls : ok) {
            if (r.body() != null) {
                body = SchemaInference.merge(body, SchemaInference.infer(r.body()));
            }
        }
        String summary = "recorded " + calls.size() + (calls.size() == 1 ? " call" : " calls");
        return new ApiEndpoint(operationId(first.method(), template), first.method(), template, summary,
                List.of("recording"), params, first.method().hasBody() ? body : null, null, Set.of("har"));
    }

    private static void addParams(List<ApiParam> params, List<Raw> calls, ParamLocation in) {
        Map<String, Schema> schemas = new LinkedHashMap<>();
        Map<String, Integer> seen = new HashMap<>();
        for (Raw r : calls) {
            Map<String, List<String>> values = in == ParamLocation.QUERY ? r.query()
                    : r.headers().entrySet().stream().collect(LinkedHashMap::new,
                    (mm, e) -> mm.put(e.getKey(), List.of(e.getValue())), Map::putAll);
            for (Map.Entry<String, List<String>> e : values.entrySet()) {
                seen.merge(e.getKey(), 1, Integer::sum);
                for (String v : e.getValue()) {
                    schemas.put(e.getKey(), SchemaInference.merge(schemas.get(e.getKey()), SchemaInference.inferText(v)));
                }
            }
        }
        for (Map.Entry<String, Schema> e : schemas.entrySet()) {
            boolean required = in == ParamLocation.QUERY && seen.get(e.getKey()) == calls.size();
            params.add(new ApiParam(e.getKey(), in, required, e.getValue(), null));
        }
    }

    /**
     * {@code GET /api/v1/orders/{orderId}/lines} → {@code getOrdersLines}; a trailing variable adds {@code ById};
     * segments up to an {@code api} segment (context path, gateway prefix) do not name the operation.
     */
    static String operationId(HttpMethod method, String template) {
        StringBuilder words = new StringBuilder(method.name().toLowerCase(Locale.ROOT));
        String[] parts = template.split("/");
        int start = 0;
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equalsIgnoreCase("api")) {
                start = i + 1;
                break;
            }
        }
        for (int i = start; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.startsWith("{") || p.equalsIgnoreCase("api") || VERSION.matcher(p).matches()) {
                continue;
            }
            words.append(' ').append(p);
        }
        if (parts.length > 0 && parts[parts.length - 1].startsWith("{")) {
            words.append(" by id");
        }
        return Names.jsIdentifier(words.toString());
    }
}
