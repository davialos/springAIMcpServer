package com.springaimcpservercommon.loadtest.data;

import com.springaimcpservercommon.loadtest.discovery.Documents;
import com.springaimcpservercommon.loadtest.discovery.HarCapture;
import com.springaimcpservercommon.loadtest.model.ApiCatalog;
import com.springaimcpservercommon.loadtest.model.ApiEndpoint;
import com.springaimcpservercommon.loadtest.model.ApiParam;
import com.springaimcpservercommon.loadtest.model.ArraySchema;
import com.springaimcpservercommon.loadtest.model.Names;
import com.springaimcpservercommon.loadtest.model.ObjectSchema;
import com.springaimcpservercommon.loadtest.model.ParamLocation;
import com.springaimcpservercommon.loadtest.model.Property;
import com.springaimcpservercommon.loadtest.model.RefSchema;
import com.springaimcpservercommon.loadtest.model.ScalarSchema;
import com.springaimcpservercommon.loadtest.model.Schema;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns recorded browser traffic ({@link HarCapture}) into suite data, against the final (merged) catalog and
 * data plan, so recorded values land under the same field keys every other data source uses:
 * <ul>
 *   <li>{@link #userData()}: every recorded value of a planned, non-sensitive field, plus whole recorded bodies
 *       per API (bodies containing a sensitive field are not kept as payloads);</li>
 *   <li>{@link #journey()}: the recorded sequence as replayable steps — values, pauses and correlations: an
 *       identifier a step sends that an earlier response returned (the id of the order just created) becomes a
 *       reference to that response, so the replay uses the id the server returns this time. Sensitive fields
 *       are removed from the steps and listed in {@code fill}, so the replay generates them instead.</li>
 * </ul>
 */
public final class RecordedTraffic {

    private static final Pattern VAR = Pattern.compile("\\{([^}]+)}");
    private static final int MAX_VALUES_PER_FIELD = 1000;

    private final List<HarCapture> captures;
    private final ApiCatalog catalog;
    private final DataPlan plan;
    private final Map<String, ApiEndpoint> byRoute = new HashMap<>();

    /**
     * Creates the mapping.
     *
     * @param captures recordings
     * @param catalog  merged catalog (after filtering)
     * @param plan     data plan of that catalog
     */
    public RecordedTraffic(List<HarCapture> captures, ApiCatalog catalog, DataPlan plan) {
        this.captures = List.copyOf(captures);
        this.catalog = catalog;
        this.plan = plan;
        for (ApiEndpoint e : catalog.endpoints()) {
            byRoute.put(e.routeKey(), e);
        }
    }

    // ── recorded values ────────────────────────────────────────────────────────────────────────────────

    /**
     * Recorded values as user data.
     *
     * @return fields and payloads
     */
    public UserData userData() {
        Map<String, List<Object>> fields = new LinkedHashMap<>();
        Map<String, List<Object>> payloads = new LinkedHashMap<>();
        for (HarCapture capture : captures) {
            for (HarCapture.Observation o : capture.observations()) {
                ApiEndpoint e = byRoute.get(o.routeKey());
                if (e == null) {
                    continue; // excluded or filtered out
                }
                Map<String, Object> path = pathValues(e, o);
                path.forEach((name, v) -> add(fields, FieldKeys.param(e.id(), ParamLocation.PATH, name), v));
                for (ApiParam p : e.params()) {
                    List<String> values = p.in() == ParamLocation.QUERY ? o.query().get(p.name())
                            : p.in() == ParamLocation.HEADER && o.headers().containsKey(p.name())
                            ? List.of(o.headers().get(p.name())) : null;
                    if (values != null) {
                        for (String v : values) {
                            add(fields, FieldKeys.param(e.id(), p.in(), p.name()), typed(p.schema(), v));
                        }
                    }
                }
                if (o.body() != null && e.body() != null) {
                    leaves(e.body(), o.body(), FieldKeys.body(e.id()), fields);
                    if (!hasSensitive(o.body())) {
                        List<Object> list = payloads.computeIfAbsent(e.id(), k -> new ArrayList<>());
                        if (!list.contains(o.body()) && list.size() < MAX_VALUES_PER_FIELD) {
                            list.add(o.body());
                        }
                    }
                }
            }
        }
        return new UserData(fields, payloads, Map.of());
    }

    private void add(Map<String, List<Object>> fields, String key, Object value) {
        FieldPlan f = plan.field(key);
        if (f == null || f.sensitive()) {
            return; // not a planned field, or a secret/personal identifier: never reused
        }
        List<Object> list = fields.computeIfAbsent(key, k -> new ArrayList<>());
        if (!list.contains(value) && list.size() < MAX_VALUES_PER_FIELD) {
            list.add(value);
        }
    }

    /** Walks a body the way {@link DataPlan} walks its schema, collecting scalar leaves under the same keys. */
    private void leaves(Schema schema, JsonNode value, String key, Map<String, List<Object>> fields) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return;
        }
        switch (schema) {
            case ScalarSchema s -> {
                if (value.isValueNode()) {
                    add(fields, key, scalar(value));
                }
            }
            case ArraySchema a -> {
                if (value.isArray()) {
                    value.forEach(v -> leaves(a.items(), v, key, fields));
                }
            }
            case ObjectSchema o -> {
                for (Map.Entry<String, Property> p : o.properties().entrySet()) {
                    if (!p.getValue().sensitive()) {
                        leaves(p.getValue().schema(), value.path(p.getKey()), FieldKeys.child(key, p.getKey()), fields);
                    }
                }
            }
            case RefSchema(String ref) -> {
                ObjectSchema target = catalog.schemas().get(ref);
                if (target != null) {
                    leaves(target, value, ref, fields);
                }
            }
        }
    }

    private static Map<String, Object> pathValues(ApiEndpoint e, HarCapture.Observation o) {
        Map<String, Object> out = new LinkedHashMap<>();
        Matcher m = VAR.matcher(e.path());
        int i = 0;
        while (m.find()) {
            if (i < o.pathValues().size()) {
                String name = m.group(1);
                Schema schema = e.params(ParamLocation.PATH).stream().filter(p -> p.name().equals(name))
                        .map(ApiParam::schema).findFirst().orElse(null);
                out.put(name, typed(schema, o.pathValues().get(i)));
            }
            i++;
        }
        return out;
    }

    private static Object typed(@Nullable Schema schema, String value) {
        if (schema instanceof ScalarSchema s) {
            try {
                return switch (s.type()) {
                    case INTEGER -> Long.valueOf(value);
                    case NUMBER -> new java.math.BigDecimal(value);
                    case BOOLEAN -> Boolean.valueOf(value);
                    case STRING -> value;
                };
            } catch (NumberFormatException e) {
                return value;
            }
        }
        return value;
    }

    private static Object scalar(JsonNode v) {
        if (v.isIntegralNumber()) {
            return v.asLong();
        }
        if (v.isNumber()) {
            return v.decimalValue();
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        return v.asString();
    }

    private static boolean hasSensitive(JsonNode node) {
        if (node.isObject()) {
            for (var e : node.properties()) {
                if (Names.isSensitive(e.getKey()) || hasSensitive(e.getValue())) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode v : node) {
                if (hasSensitive(v)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ── journey ────────────────────────────────────────────────────────────────────────────────────────

    /** Where an identifier value was first returned: step index and dotted path in its response. */
    private record Source(int step, String at) {
    }

    /**
     * The recorded sequence as replay steps ({@code data/journey.json}).
     *
     * @return steps: {@code {api, path, query, headers, body, fill, pauseMs, recordedStatus}}
     */
    public ArrayNode journey() {
        ArrayNode steps = Documents.json().createArrayNode();
        Map<String, List<Source>> returned = new HashMap<>();
        long previousStart = -1;
        for (HarCapture capture : captures) {
            for (HarCapture.Observation o : capture.observations()) {
                ApiEndpoint e = byRoute.get(o.routeKey());
                if (e == null) {
                    continue;
                }
                ObjectNode step = steps.addObject();
                step.put("api", e.id());
                ObjectNode path = step.putObject("path");
                pathValues(e, o).forEach((name, v) -> path.set(name, correlate(json(v), returned)));
                ObjectNode query = step.putObject("query");
                o.query().forEach((name, values) -> {
                    if (values.size() == 1) {
                        query.set(name, idLike(name) ? correlate(json(values.getFirst()), returned)
                                : json(values.getFirst()));
                    } else {
                        ArrayNode arr = query.putArray(name);
                        values.forEach(v -> arr.add(v));
                    }
                });
                ObjectNode headers = step.putObject("headers");
                o.headers().forEach(headers::put);
                ArrayNode fill = Documents.json().createArrayNode();
                if (o.body() != null) {
                    step.set("body", sanitize(o.body(), "", fill, returned, null));
                }
                step.set("fill", fill);
                step.put("pauseMs", previousStart < 0 || o.startedAtMs() <= 0 ? 0
                        : Math.max(0, o.startedAtMs() - previousStart));
                step.put("recordedStatus", o.status());
                previousStart = o.startedAtMs();
                if (o.response() != null) {
                    index(o.response(), "", steps.size() - 1, returned, 0, null);
                }
            }
        }
        return steps;
    }

    private static JsonNode json(Object v) {
        return Documents.json().valueToTree(v);
    }

    /** Copy of a body without sensitive fields (their paths go to {@code fill}); id-like leaves correlated. */
    private JsonNode sanitize(JsonNode node, String path, ArrayNode fill, Map<String, List<Source>> returned,
                              @Nullable String key) {
        if (node.isObject()) {
            ObjectNode out = Documents.json().createObjectNode();
            for (var e : node.properties()) {
                String p = path.isEmpty() ? e.getKey() : path + "." + e.getKey();
                if (Names.isSensitive(e.getKey())) {
                    fill.add(p);
                } else {
                    out.set(e.getKey(), sanitize(e.getValue(), p, fill, returned, e.getKey()));
                }
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = Documents.json().createArrayNode();
            int i = 0;
            for (JsonNode v : node) {
                out.add(sanitize(v, path + "." + i++, fill, returned, key));
            }
            return out;
        }
        return key != null && idLike(key) ? correlate(node, returned) : node;
    }

    /**
     * A reference to the latest response that returned the value, with up to three earlier ones as fallbacks
     * (a response may omit the field this run); the recorded value is the last resort.
     */
    private static JsonNode correlate(JsonNode value, Map<String, List<Source>> returned) {
        if (!value.isValueNode() || value.isNull()) {
            return value;
        }
        List<Source> sources = returned.get(value.asString());
        if (sources == null || sources.isEmpty()) {
            return value;
        }
        Source latest = sources.getLast();
        ObjectNode ref = Documents.json().createObjectNode();
        ref.put("$from", latest.step());
        ref.put("at", latest.at());
        ArrayNode alt = Documents.json().createArrayNode();
        for (int i = sources.size() - 2; i >= 0 && alt.size() < 3; i--) {
            alt.addObject().put("$from", sources.get(i).step()).put("at", sources.get(i).at());
        }
        if (!alt.isEmpty()) {
            ref.set("alt", alt);
        }
        ref.set("recorded", value);
        return ref;
    }

    /** Remembers id-like values a response returned (in step order), up to a bounded depth and fan-out. */
    private static void index(JsonNode node, String path, int step, Map<String, List<Source>> returned, int depth,
                              @Nullable String key) {
        if (depth > 5) {
            return;
        }
        if (node.isObject()) {
            for (var e : node.properties()) {
                index(e.getValue(), path.isEmpty() ? e.getKey() : path + "." + e.getKey(), step, returned, depth + 1,
                        e.getKey());
            }
        } else if (node.isArray()) {
            int i = 0;
            for (JsonNode v : node) {
                if (i >= 100) {
                    break;
                }
                index(v, path + "." + i++, step, returned, depth + 1, key);
            }
        } else if (key != null && idLike(key) && !Names.isSensitive(key) && !node.isNull() && !node.isBoolean()
                && node.asString().length() >= 1) {
            List<Source> sources = returned.computeIfAbsent(node.asString(), k -> new ArrayList<>());
            if (sources.isEmpty() || sources.getLast().step() != step) { // first occurrence per response
                sources.add(new Source(step, path));
            }
        }
    }

    /** {@code id}, {@code orderId}, {@code customer_uuid}, {@code sku}, {@code orderNumber}, {@code ref}. */
    static boolean idLike(String key) {
        String n = Names.normalize(key);
        String[] words = Names.snakeCase(key).split("[^a-z0-9]+");
        String last = words.length == 0 ? n : words[words.length - 1];
        return n.equals("id") || last.equals("id") || n.endsWith("uuid") || n.endsWith("guid") || last.equals("sku")
                || last.equals("ref") || last.equals("number") && !n.contains("phone") || last.equals("key")
                && !Names.isSensitive(key) || last.equals("code") && n.length() > 4 || last.equals("slug");
    }
}
