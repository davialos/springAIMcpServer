package com.springaimcpservercommon.celfaker.importer;

import com.springaimcpservercommon.celfaker.contract.ApiRole;
import com.springaimcpservercommon.celfaker.contract.ApiSpec;
import com.springaimcpservercommon.celfaker.expr.Literals;
import com.springaimcpservercommon.celfaker.payload.Candidate;
import com.springaimcpservercommon.celfaker.payload.PayloadAnalyzer;
import com.springaimcpservercommon.ruleengine.model.DataType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns a Swagger 2 / OpenAPI 3 document into {@link ApiSpec}s: path, method, documentation, example request and response
 * bodies built from the schemas, accepted statuses, auth header placeholders and CEL rules derived from the schema
 * constraints (minimum, maximum, lengths, enum, pattern). GET operations named validate / verify / check become VALIDATION APIs.
 */
public final class OpenApiImporter {

    private static final List<String> METHODS = List.of("get", "post", "put", "patch", "delete");

    private OpenApiImporter() {
    }

    /**
     * Imports every operation.
     *
     * @param doc    the parsed document
     * @param source URL the document came from (to resolve a relative server URL), or empty
     * @return the APIs, the service base URL and warnings
     */
    public static Imported parse(JsonNode doc, String source) {
        SchemaExamples ex = new SchemaExamples(doc);
        List<String> warnings = new ArrayList<>();
        boolean v2 = doc.has("swagger");
        String[] server = server(doc, source, v2);
        String base = server[0];
        String prefix = server[1];
        List<ApiSpec> apis = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Map.Entry<String, JsonNode> p : doc.path("paths").properties()) {
            JsonNode item = ex.resolve(p.getValue());
            for (String method : METHODS) {
                JsonNode op = item.path(method);
                if (!op.isObject()) {
                    continue;
                }
                try {
                    apis.add(operation(doc, ex, v2, prefix + p.getKey(), method, op, item.path("parameters"), ids, warnings));
                } catch (RuntimeException e) {
                    warnings.add(method.toUpperCase(Locale.ROOT) + " " + p.getKey() + " skipped: " + e.getMessage());
                }
            }
        }
        return new Imported(doc.path("info").path("title").asString("API"), base, apis, warnings);
    }

    private static String[] server(JsonNode doc, String source, boolean v2) {
        String url = "";
        if (v2) {
            String scheme = doc.path("schemes").isArray() && !doc.path("schemes").isEmpty() ? doc.path("schemes").get(0).asString("http") : "http";
            String host = doc.path("host").asString("");
            url = host.isEmpty() ? "" : scheme + "://" + host + doc.path("basePath").asString("");
        } else if (doc.path("servers").isArray() && !doc.path("servers").isEmpty()) {
            JsonNode s = doc.path("servers").get(0);
            url = s.path("url").asString("");
            for (Map.Entry<String, JsonNode> v : s.path("variables").properties()) {
                url = url.replace("{" + v.getKey() + "}", v.getValue().path("default").asString(""));
            }
        }
        try {
            URI origin = source.isEmpty() ? null : URI.create(source);
            if (url.isEmpty() && origin != null) {
                url = origin.getScheme() + "://" + origin.getRawAuthority();
            } else if (url.startsWith("/") && origin != null) {
                url = origin.getScheme() + "://" + origin.getRawAuthority() + url;
            }
            if (!url.contains("://")) {
                return new String[]{"", url.startsWith("/") ? url.replaceAll("/+$", "") : ""};
            }
            URI u = URI.create(url);
            String path = u.getRawPath() == null ? "" : u.getRawPath().replaceAll("/+$", "");
            return new String[]{u.getScheme() + "://" + u.getRawAuthority(), path};
        } catch (IllegalArgumentException e) {
            return new String[]{"", ""};
        }
    }

    private static ApiSpec operation(JsonNode doc, SchemaExamples ex, boolean v2, String path, String method, JsonNode op,
                                     JsonNode pathParams, Set<String> ids, List<String> warnings) {
        String baseId = op.path("operationId").isString() && !op.path("operationId").asString().isBlank()
                ? op.path("operationId").asString().replaceAll("[^A-Za-z0-9_-]", "_") : CurlParser.id(method, path);
        String id = baseId;
        for (int n = 2; !ids.add(id); n++) {
            id = baseId + n;
        }
        String finalPath = path;
        List<JsonNode> params = new ArrayList<>();
        pathParams.forEach(params::add);
        op.path("parameters").forEach(params::add);
        StringBuilder query = new StringBuilder();
        JsonNode body = MissingNode.getInstance();
        JsonNode bodySchema = null;
        for (JsonNode raw : params) {
            JsonNode prm = ex.resolve(raw);
            String in = prm.path("in").asString("");
            if (in.equals("query") && prm.path("required").asBoolean(false)) {
                JsonNode schema = v2 ? prm : prm.path("schema");
                query.append(query.length() == 0 ? '?' : '&').append(prm.path("name").asString())
                        .append('=').append(String.valueOf(ex.example(schema, prm.path("name").asString()).asString("1")));
            } else if (in.equals("body") && v2) {
                bodySchema = prm.path("schema");
                body = ex.example(bodySchema, "body");
            }
        }
        JsonNode schema = jsonSchema(ex, op.path("requestBody").path("content"));
        if (schema != null) {
            bodySchema = schema;
            body = ex.example(schema, "body");
        }
        // responses
        List<Integer> ok = new ArrayList<>();
        List<Integer> bad = new ArrayList<>();
        JsonNode response = MissingNode.getInstance();
        for (Map.Entry<String, JsonNode> r : op.path("responses").properties()) {
            if (!r.getKey().matches("\\d{3}")) {
                continue;
            }
            int code = Integer.parseInt(r.getKey());
            if (code >= 200 && code < 300) {
                ok.add(code);
                if (response.isMissingNode()) {
                    JsonNode rs = ex.resolve(r.getValue());
                    JsonNode sc = v2 ? rs.path("schema") : jsonSchema(ex, rs.path("content"));
                    if (sc != null && !sc.isMissingNode()) {
                        response = ex.example(sc, "response");
                    }
                }
            } else if (code == 400 || code == 422) {
                bad.add(code);
            }
        }
        Map<String, String> headers = security(doc, op, ex);
        boolean validation = method.equals("get") && path.toLowerCase(Locale.ROOT).matches(".*/(validate|verify|check)[a-z-]*(/.*|\\{.*)?$");
        String text = (op.path("summary").asString("") + "\n" + op.path("description").asString("")).strip();
        String name = op.path("summary").asString(id);
        List<String> rules = body.isObject() && bodySchema != null ? rules(ex, bodySchema, body, id) : List.of();
        if (!op.path("requestBody").path("content").isMissingNode() && schema == null && !op.path("requestBody").isMissingNode()) {
            warnings.add(method.toUpperCase(Locale.ROOT) + " " + path + ": request body is not JSON; no fake body generated");
        }
        return new ApiSpec(id, name, method, finalPath + query, text, validation ? ApiRole.VALIDATION : ApiRole.ACTION, null,
                headers, body.isObject() ? body : null, response.isMissingNode() ? null : response, List.of(), List.of(), rules,
                ok, bad, null, null);
    }

    private static JsonNode jsonSchema(SchemaExamples ex, JsonNode content) {
        if (!content.isObject()) {
            return null;
        }
        for (Map.Entry<String, JsonNode> c : content.properties()) {
            if (c.getKey().contains("json") && c.getValue().has("schema")) {
                return c.getValue().path("schema");
            }
        }
        return null;
    }

    private static Map<String, String> security(JsonNode doc, JsonNode op, SchemaExamples ex) {
        JsonNode req = op.has("security") ? op.path("security") : doc.path("security");
        Map<String, String> headers = new LinkedHashMap<>();
        if (!req.isArray() || req.isEmpty()) {
            return headers;
        }
        JsonNode schemes = doc.path("components").path("securitySchemes").isObject() ? doc.path("components").path("securitySchemes") : doc.path("securityDefinitions");
        for (Map.Entry<String, JsonNode> name : req.get(0).properties()) {
            JsonNode s = ex.resolve(schemes.path(name.getKey()));
            String type = s.path("type").asString("");
            if (type.equals("apiKey") && s.path("in").asString("").equals("header")) {
                headers.put(s.path("name").asString("X-API-Key"), "{{env.API_KEY}}");
            } else if (type.equals("oauth2") || (type.equals("http") && s.path("scheme").asString("").equalsIgnoreCase("bearer"))) {
                headers.put("Authorization", "Bearer {{env.TOKEN}}");
            } else if (type.equals("basic") || (type.equals("http") && s.path("scheme").asString("").equalsIgnoreCase("basic"))) {
                headers.put("Authorization", "Basic {{env.BASIC_AUTH}}");
            }
        }
        return headers;
    }

    /** CEL rules from schema constraints, named with the same sys object / attribute scheme the payload analyzer uses. */
    private static List<String> rules(SchemaExamples ex, JsonNode schema, JsonNode example, String apiId) {
        String sysObject = apiId.replaceAll("[^A-Za-z0-9_]", "_");
        Map<String, Candidate> byPath = new HashMap<>();
        for (Candidate c : PayloadAnalyzer.analyze(example, sysObject).candidates()) {
            byPath.put(c.path(), c);
        }
        List<String> out = new ArrayList<>();
        collect(ex, schema, "", byPath, out, 0);
        return out;
    }

    private static void collect(SchemaExamples ex, JsonNode raw, String prefix, Map<String, Candidate> byPath, List<String> out, int depth) {
        JsonNode s = ex.resolve(raw);
        if (depth > 6) {
            return;
        }
        if (s.path("allOf").isArray()) {
            s.path("allOf").forEach(part -> collect(ex, part, prefix, byPath, out, depth + 1));
        }
        for (Map.Entry<String, JsonNode> p : s.path("properties").properties()) {
            String path = prefix.isEmpty() ? p.getKey() : prefix + "." + p.getKey();
            JsonNode prop = ex.resolve(p.getValue());
            if (prop.path("type").asString("").equals("object") || prop.has("properties") || prop.has("allOf")) {
                collect(ex, prop, path, byPath, out, depth + 1);
                continue;
            }
            Candidate c = byPath.get(path);
            if (c != null) {
                constraints(prop, c, out);
            }
        }
    }

    private static void constraints(JsonNode s, Candidate c, List<String> out) {
        String n = c.celName();
        DataType t = c.type();
        boolean numeric = t == DataType.INT || t == DataType.DOUBLE;
        if (numeric) {
            bound(s, "minimum", n, ">=", t, out);
            bound(s, "maximum", n, "<=", t, out);
            if (s.path("exclusiveMinimum").isNumber()) {
                out.add(n + " > " + Literals.of(t, s.path("exclusiveMinimum").numberValue()));
            }
            if (s.path("exclusiveMaximum").isNumber()) {
                out.add(n + " < " + Literals.of(t, s.path("exclusiveMaximum").numberValue()));
            }
        }
        if (t == DataType.STRING) {
            if (s.has("minLength")) {
                out.add(n + ".size() >= " + s.path("minLength").asInt());
            }
            if (s.has("maxLength")) {
                out.add(n + ".size() <= " + s.path("maxLength").asInt());
            }
            if (s.path("pattern").isString()) {
                out.add(n + ".matches(" + Literals.string(s.path("pattern").stringValue()) + ")");
            }
        }
        if (t == DataType.LIST_STRING || t == DataType.LIST_INT || t == DataType.LIST_DOUBLE) {
            if (s.has("minItems")) {
                out.add(n + ".size() >= " + s.path("minItems").asInt());
            }
            if (s.has("maxItems")) {
                out.add(n + ".size() <= " + s.path("maxItems").asInt());
            }
        }
        if (s.path("enum").isArray() && !s.path("enum").isEmpty() && (numeric || t == DataType.STRING)) {
            List<String> lits = new ArrayList<>();
            for (JsonNode e : s.path("enum")) {
                if (t == DataType.STRING ? e.isString() : e.isNumber()) {
                    lits.add(Literals.of(t, t == DataType.STRING ? e.stringValue() : e.numberValue()));
                }
            }
            if (!lits.isEmpty()) {
                out.add(n + " in [" + lits.stream().collect(Collectors.joining(", ")) + "]");
            }
        }
    }

    private static void bound(JsonNode s, String key, String n, String op, DataType t, List<String> out) {
        if (s.path(key).isNumber() && !s.path("exclusive" + Character.toUpperCase(key.charAt(0)) + key.substring(1)).asBoolean(false)) {
            out.add(n + " " + op + " " + Literals.of(t, s.path(key).numberValue()));
        } else if (s.path(key).isNumber()) {
            out.add(n + " " + (op.equals(">=") ? ">" : "<") + " " + Literals.of(t, s.path(key).numberValue()));
        }
    }
}
