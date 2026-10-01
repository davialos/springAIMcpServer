package com.springaimcpservercommon.loadtest.discovery;

import com.springaimcpservercommon.loadtest.model.ApiCatalog;
import com.springaimcpservercommon.loadtest.model.ApiEndpoint;
import com.springaimcpservercommon.loadtest.model.ApiParam;
import com.springaimcpservercommon.loadtest.model.ArraySchema;
import com.springaimcpservercommon.loadtest.model.Constraints;
import com.springaimcpservercommon.loadtest.model.HttpMethod;
import com.springaimcpservercommon.loadtest.model.Names;
import com.springaimcpservercommon.loadtest.model.ObjectSchema;
import com.springaimcpservercommon.loadtest.model.ParamLocation;
import com.springaimcpservercommon.loadtest.model.Property;
import com.springaimcpservercommon.loadtest.model.RefSchema;
import com.springaimcpservercommon.loadtest.model.ScalarSchema;
import com.springaimcpservercommon.loadtest.model.ScalarType;
import com.springaimcpservercommon.loadtest.model.Schema;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Reads an OpenAPI 3.0/3.1 document (JSON or YAML, e.g. springdoc's {@code /v3/api-docs}) into an
 * {@link ApiCatalog}. Supports {@code $ref} to components (schemas, parameters, request bodies), {@code allOf}
 * merging, {@code oneOf}/{@code anyOf} (first branch), 3.1 type arrays and skips {@code readOnly} properties.
 */
public final class OpenApiReader {

    private static final List<String> METHODS = List.of("get", "put", "post", "delete", "patch", "head", "options");

    private final Consumer<String> log;
    private JsonNode root;
    private final Map<String, ObjectSchema> schemas = new LinkedHashMap<>();
    private final Set<String> inProgress = new HashSet<>();

    /**
     * Creates a reader.
     *
     * @param log receives notes about skipped operations
     */
    public OpenApiReader(Consumer<String> log) {
        this.log = log;
        this.root = Documents.parse("{}");
    }

    /**
     * Parses a document.
     *
     * @param document OpenAPI text (JSON or YAML)
     * @return the catalog
     */
    public ApiCatalog read(String document) {
        root = Documents.parse(document);
        schemas.clear();
        if (!root.path("openapi").isString() && !root.path("swagger").isMissingNode()) {
            throw new IllegalArgumentException("Only OpenAPI 3.x documents are supported (found Swagger 2)");
        }
        String title = root.path("info").path("title").asString("project");
        List<ApiEndpoint> endpoints = new ArrayList<>();
        for (var pathEntry : root.path("paths").properties()) {
            String path = pathEntry.getKey();
            JsonNode item = pathEntry.getValue();
            for (String m : METHODS) {
                JsonNode op = item.path(m);
                if (op.isMissingNode()) {
                    continue;
                }
                ApiEndpoint e = operation(HttpMethod.parse(m), path, item, op);
                if (e != null) {
                    endpoints.add(e);
                }
            }
        }
        log.accept("openapi: " + endpoints.size() + " operations, " + schemas.size() + " schemas");
        return new ApiCatalog(title, basePath(), endpoints, schemas, List.of());
    }

    private @Nullable String basePath() {
        JsonNode server = root.path("servers").path(0).path("url");
        if (!server.isString()) {
            return null;
        }
        try {
            String p = URI.create(server.asString()).getPath();
            p = p == null ? "" : p.replaceAll("/+$", "");
            return p.isEmpty() ? null : p;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private @Nullable ApiEndpoint operation(HttpMethod method, String path, JsonNode item, JsonNode op) {
        String id = op.path("operationId").asString(
                Names.jsIdentifier(method.name().toLowerCase(Locale.ROOT) + " " + path.replaceAll("[{}]", "")));
        Map<String, ApiParam> params = new LinkedHashMap<>();
        for (JsonNode list : List.of(item.path("parameters"), op.path("parameters"))) {
            for (JsonNode raw : list) {
                JsonNode p = deref(raw);
                String in = p.path("in").asString("");
                ParamLocation loc = switch (in) {
                    case "path" -> ParamLocation.PATH;
                    case "query" -> ParamLocation.QUERY;
                    case "header" -> ParamLocation.HEADER;
                    default -> null;
                };
                String name = p.path("name").asString("");
                if (loc == null || name.isEmpty() || (loc == ParamLocation.HEADER
                        && name.equalsIgnoreCase("Authorization"))) {
                    continue;
                }
                Schema s = withExample(schema(p.path("schema")), p.path("example"));
                JsonNode def = p.path("schema").path("default");
                params.put(in + ":" + name, new ApiParam(name, loc,
                        loc == ParamLocation.PATH || p.path("required").asBoolean(false), s,
                        def.isMissingNode() || def.isNull() ? null : def.asString()));
            }
        }
        Schema body = null;
        JsonNode rb = deref(op.path("requestBody"));
        if (!rb.isMissingNode()) {
            JsonNode content = rb.path("content");
            JsonNode json = null;
            for (var c : content.properties()) {
                if (c.getKey().contains("json")) {
                    json = c.getValue();
                    break;
                }
            }
            if (json == null && !content.isEmpty()) {
                log.accept("openapi: skipped " + method + " " + path + " (request body is not JSON: "
                        + String.join(", ", content.propertyNames()) + ")");
                return null;
            }
            if (json != null) {
                body = schema(json.path("schema"));
            }
        }
        List<String> tags = new ArrayList<>();
        op.path("tags").forEach(t -> tags.add(t.asString()));
        String summary = op.path("summary").isString() ? op.path("summary").asString() : null;
        return new ApiEndpoint(id, method, path, summary, tags, new ArrayList<>(params.values()), body, null,
                Set.of("openapi"));
    }

    private JsonNode deref(JsonNode node) {
        JsonNode current = node;
        for (int i = 0; i < 10 && current.path("$ref").isString(); i++) {
            String ref = current.path("$ref").asString();
            current = ref.startsWith("#") ? root.at(ref.substring(1)) : current.path("__unresolved__");
        }
        return current;
    }

    private Schema withExample(Schema s, JsonNode example) {
        if (s instanceof ScalarSchema sc && !example.isMissingNode() && !example.isNull() && !example.isContainer()) {
            return sc.withExample(example.asString());
        }
        return s;
    }

    private Schema schema(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return ObjectSchema.freeFormObject();
        }
        if (node.path("$ref").isString()) {
            String ref = node.path("$ref").asString();
            String name = ref.substring(ref.lastIndexOf('/') + 1);
            JsonNode target = deref(node);
            if (!isObject(target)) {
                return inProgress.contains(name) ? ObjectSchema.freeFormObject() : guarded(name, target);
            }
            if (!schemas.containsKey(name) && inProgress.add(name)) {
                try {
                    Schema s = schema(target);
                    schemas.put(name, s instanceof ObjectSchema o ? o : ObjectSchema.freeFormObject());
                } finally {
                    inProgress.remove(name);
                }
            }
            return new RefSchema(name);
        }
        if (node.has("allOf")) {
            Map<String, Property> merged = new LinkedHashMap<>();
            for (JsonNode part : node.path("allOf")) {
                Schema s = schema(part);
                ObjectSchema o = s instanceof RefSchema(String n) ? schemas.get(n)
                        : s instanceof ObjectSchema os ? os : null;
                if (o != null) {
                    merged.putAll(o.properties());
                }
            }
            if (node.has("properties")) {
                Schema own = objectSchema(node);
                if (own instanceof ObjectSchema os) {
                    merged.putAll(os.properties());
                }
            }
            return new ObjectSchema(merged, merged.isEmpty());
        }
        for (String combinator : List.of("oneOf", "anyOf")) {
            if (node.has(combinator)) {
                for (JsonNode branch : node.path(combinator)) {
                    if (!"null".equals(branch.path("type").asString(""))) {
                        return schema(branch);
                    }
                }
            }
        }
        String type = type(node);
        return switch (type) {
            case "object" -> objectSchema(node);
            case "array" -> new ArraySchema(schema(node.path("items")), intOrNull(node.path("minItems")),
                    intOrNull(node.path("maxItems")));
            case "string", "integer", "number", "boolean" -> scalar(type, node);
            default -> node.has("properties") ? objectSchema(node) : ObjectSchema.freeFormObject();
        };
    }

    private Schema guarded(String name, JsonNode target) {
        inProgress.add(name);
        try {
            return schema(target);
        } finally {
            inProgress.remove(name);
        }
    }

    private static boolean isObject(JsonNode n) {
        return n.has("properties") || n.has("allOf") || "object".equals(type(n));
    }

    private static String type(JsonNode node) {
        JsonNode t = node.path("type");
        if (t.isArray()) {
            for (JsonNode x : t) {
                if (!"null".equals(x.asString())) {
                    return x.asString();
                }
            }
        }
        return t.asString("");
    }

    private Schema objectSchema(JsonNode node) {
        Set<String> required = new HashSet<>();
        node.path("required").forEach(r -> required.add(r.asString()));
        Map<String, Property> props = new LinkedHashMap<>();
        for (var e : node.path("properties").properties()) {
            JsonNode p = e.getValue();
            if (p.path("readOnly").asBoolean(false)) {
                continue;
            }
            Schema s = schema(p);
            boolean sensitive = Names.isSensitive(e.getKey()) || "password".equals(p.path("format").asString(""));
            String description = p.path("description").isString() ? p.path("description").asString() : null;
            props.put(e.getKey(), new Property(s, required.contains(e.getKey()), sensitive, description));
        }
        return new ObjectSchema(props, props.isEmpty());
    }

    private static ScalarSchema scalar(String type, JsonNode n) {
        ScalarType t = switch (type) {
            case "integer" -> ScalarType.INTEGER;
            case "number" -> ScalarType.NUMBER;
            case "boolean" -> ScalarType.BOOLEAN;
            default -> ScalarType.STRING;
        };
        List<String> enums = new ArrayList<>();
        n.path("enum").forEach(v -> {
            if (!v.isNull()) {
                enums.add(v.asString());
            }
        });
        BigDecimal min = decimal(n.path("minimum"));
        BigDecimal max = decimal(n.path("maximum"));
        if (n.path("exclusiveMinimum").isNumber()) {
            min = n.path("exclusiveMinimum").decimalValue().add(t == ScalarType.INTEGER ? BigDecimal.ONE
                    : new BigDecimal("0.01"));
        }
        if (n.path("exclusiveMaximum").isNumber()) {
            max = n.path("exclusiveMaximum").decimalValue().subtract(t == ScalarType.INTEGER ? BigDecimal.ONE
                    : new BigDecimal("0.01"));
        }
        Constraints c = new Constraints(longOrNull(n.path("minLength")), longOrNull(n.path("maxLength")), min, max,
                n.path("pattern").isString() ? n.path("pattern").asString() : null, null);
        JsonNode example = n.has("example") ? n.path("example") : n.path("examples").path(0);
        String ex = example.isMissingNode() || example.isNull() || example.isContainer() ? null : example.asString();
        return new ScalarSchema(t, n.path("format").isString() ? n.path("format").asString() : null, c, enums, ex);
    }

    private static @Nullable BigDecimal decimal(JsonNode n) {
        return n.isNumber() ? n.decimalValue() : null;
    }

    private static @Nullable Long longOrNull(JsonNode n) {
        return n.isNumber() ? Long.valueOf(n.asLong()) : null;
    }

    private static @Nullable Integer intOrNull(JsonNode n) {
        return n.isNumber() ? Integer.valueOf(n.asInt()) : null;
    }
}
