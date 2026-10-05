package com.springaimcpservercommon.loadtest.discovery;

import com.springaimcpservercommon.loadtest.model.ApiCatalog;
import com.springaimcpservercommon.loadtest.model.ApiEndpoint;
import com.springaimcpservercommon.loadtest.model.Constraints;
import com.springaimcpservercommon.loadtest.model.ArraySchema;
import com.springaimcpservercommon.loadtest.model.HttpMethod;
import com.springaimcpservercommon.loadtest.model.Names;
import com.springaimcpservercommon.loadtest.model.ObjectSchema;
import com.springaimcpservercommon.loadtest.model.Property;
import com.springaimcpservercommon.loadtest.model.ScalarSchema;
import com.springaimcpservercommon.loadtest.model.ScalarType;
import com.springaimcpservercommon.loadtest.model.Schema;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads GraphQL schema files (Spring GraphQL's {@code *.graphqls}) into endpoints: every {@code Query} and
 * {@code Mutation} field becomes a {@code POST <graphql path>} whose body is {@code {operationName, query, variables}}
 * — the query document is generated (arguments as variables, a selection of the return type's scalar fields and one
 * level of nested objects), the variables follow the argument types (input objects, enums, lists, custom scalars).
 * Subscriptions are skipped (they need a WebSocket). A GraphQL service answers {@code 200} with an {@code errors}
 * member on failure, so the suite also checks for that.
 */
public final class GraphQlSchemaReader {

    /** Tag every GraphQL endpoint carries. */
    public static final String TAG = "graphql";
    private static final int MAX_SELECTION = 30;
    private static final Pattern DEFINITION = Pattern.compile(
            "(?:extend\\s+)?(type|input|interface|enum|union|scalar)\\s+(\\w+)([^{=\\n]*)(?:\\{([^}]*)}|=\\s*([^\\n]+))?");
    private static final Pattern FIELD = Pattern.compile(
            "(\\w+)\\s*(?:\\(([^)]*)\\))?\\s*:\\s*([\\[\\]\\w!]+)");
    private static final Pattern ARG = Pattern.compile("(\\w+)\\s*:\\s*([\\[\\]\\w!]+)(?:\\s*=\\s*[^,\\s)]+)?");

    private record Field(String name, List<Arg> args, String type) {
    }

    private record Arg(String name, String type) {
    }

    private record Definition(String kind, String name, List<Field> fields, List<String> values) {
    }

    private final Consumer<String> log;
    private final Map<String, Definition> types = new LinkedHashMap<>();

    /**
     * Creates a reader.
     *
     * @param log receives notes about what was found
     */
    public GraphQlSchemaReader(Consumer<String> log) {
        this.log = log;
    }

    /**
     * Reads the project's GraphQL schema files.
     *
     * @param projectDir project root
     * @param settings   its Spring settings ({@code spring.graphql.path})
     * @return the catalog, or {@code null} when the project has no GraphQL schema
     */
    public @Nullable ApiCatalog read(Path projectDir, ProjectSettings settings) {
        List<Path> files = ProjectFiles.graphQlSchemas(projectDir);
        if (files.isEmpty()) {
            return null;
        }
        StringBuilder sdl = new StringBuilder();
        for (Path f : files) {
            try {
                sdl.append(Files.readString(f)).append('\n');
            } catch (IOException e) {
                log.accept("graphql: cannot read " + f + ": " + e.getMessage());
            }
        }
        parse(sdl.toString());
        String path = settings.properties().getOrDefault("spring.graphql.path", "/graphql");
        List<ApiEndpoint> endpoints = new ArrayList<>();
        for (String root : List.of("Query", "Mutation")) {
            Definition d = types.get(root);
            if (d == null) {
                continue;
            }
            for (Field f : d.fields()) {
                endpoints.add(endpoint(root.equals("Query") ? "query" : "mutation", f, path));
            }
        }
        log.accept("graphql: " + endpoints.size() + " operations from " + files.size() + " schema file(s), POST " + path);
        return new ApiCatalog(projectDir.toAbsolutePath().normalize().getFileName() + "-graphql", null, endpoints,
                Map.of(), List.of());
    }

    // ── SDL ────────────────────────────────────────────────────────────────────────────────────────────

    private void parse(String sdl) {
        String clean = sdl.replaceAll("(?s)\"\"\".*?\"\"\"", " ").replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", " ")
                .replaceAll("(?m)#.*$", " ").replaceAll("@\\w+(\\([^)]*\\))?", " ").replaceAll("implements[^{]*", " ");
        Matcher m = DEFINITION.matcher(clean);
        while (m.find()) {
            String kind = m.group(1);
            String name = m.group(2);
            String body = m.group(4) == null ? "" : m.group(4);
            List<Field> fields = new ArrayList<>();
            List<String> values = new ArrayList<>();
            if (kind.equals("enum")) {
                for (String v : body.trim().split("\\s+")) {
                    if (!v.isBlank()) {
                        values.add(v);
                    }
                }
            } else if (kind.equals("union")) {
                String members = m.group(5) == null ? "" : m.group(5);
                for (String v : members.split("[|\\s]+")) {
                    if (!v.isBlank()) {
                        values.add(v);
                    }
                }
            } else if (kind.equals("type") || kind.equals("input") || kind.equals("interface")) {
                Matcher fm = FIELD.matcher(body);
                while (fm.find()) {
                    List<Arg> args = new ArrayList<>();
                    if (fm.group(2) != null) {
                        Matcher am = ARG.matcher(fm.group(2));
                        while (am.find()) {
                            args.add(new Arg(am.group(1), am.group(2)));
                        }
                    }
                    fields.add(new Field(fm.group(1), args, fm.group(3)));
                }
            }
            Definition old = types.get(name);
            if (old != null && (kind.equals("type") || kind.equals("input"))) {
                fields.addAll(0, old.fields()); // extend type Query { … }
            }
            types.put(name, new Definition(kind, name, fields, values));
        }
    }

    // ── operations ─────────────────────────────────────────────────────────────────────────────────────

    private ApiEndpoint endpoint(String operation, Field f, String path) {
        String opName = f.name();
        StringBuilder decl = new StringBuilder();
        StringBuilder call = new StringBuilder();
        Map<String, Property> variables = new LinkedHashMap<>();
        for (Arg a : f.args()) {
            decl.append(decl.isEmpty() ? "" : ", ").append('$').append(a.name()).append(": ").append(a.type());
            call.append(call.isEmpty() ? "" : ", ").append(a.name()).append(": $").append(a.name());
            variables.put(a.name(), new Property(schemaOf(a.type(), new HashSet<>()), a.type().endsWith("!"), false,
                    null));
        }
        String doc = operation + " " + opName + (decl.isEmpty() ? "" : "(" + decl + ")") + " { " + opName
                + (call.isEmpty() ? "" : "(" + call + ")") + selection(base(f.type())) + " }";
        Map<String, Property> body = new LinkedHashMap<>();
        body.put("operationName", new Property(constant(opName), true, false, null));
        body.put("query", new Property(constant(doc), true, false, null));
        if (!variables.isEmpty()) {
            body.put("variables", new Property(new ObjectSchema(variables, false), true, false, null));
        }
        ObjectNode response = ResponseSchemas.object();
        ResponseSchemas.property(response, "data", ResponseSchemas.object().put("nullable", true), false);
        ResponseSchemas.property(response, "errors", ResponseSchemas.array(ResponseSchemas.any()), false);
        return new ApiEndpoint(Names.jsIdentifier("gql_" + opName), HttpMethod.POST, path,
                operation + " " + opName, List.of(TAG, TAG + ":" + operation), List.of(), new ObjectSchema(body, false),
                null, Set.of("graphql"), response);
    }

    private static ScalarSchema constant(String value) {
        return new ScalarSchema(ScalarType.STRING, null, Constraints.NONE,
                List.of(value), null);
    }

    private static String base(String type) {
        return type.replaceAll("[\\[\\]!]", "");
    }

    /** The selection set for a return type: its scalar and enum fields, then one level of nested objects. */
    private String selection(String returnType) {
        Definition d = types.get(returnType);
        if (d == null || d.kind().equals("scalar") || d.kind().equals("enum")) {
            return "";
        }
        if (d.kind().equals("union")) {
            return " { __typename }";
        }
        StringBuilder out = new StringBuilder(" { ");
        int count = select(d, 0, out, new HashSet<>());
        return count == 0 ? " { __typename }" : out.append("}").toString();
    }

    private int select(Definition d, int depth, StringBuilder out, Set<String> seen) {
        int count = 0;
        if (!seen.add(d.name())) {
            return 0;
        }
        for (Field f : d.fields()) {
            if (count >= MAX_SELECTION || !f.args().isEmpty()) {
                continue;
            }
            Definition target = types.get(base(f.type()));
            if (target == null || target.kind().equals("scalar") || target.kind().equals("enum")) {
                out.append(f.name()).append(' ');
                count++;
            } else if (depth < 1 && (target.kind().equals("type") || target.kind().equals("interface"))) {
                StringBuilder inner = new StringBuilder();
                if (select(target, depth + 1, inner, new HashSet<>(seen)) > 0) {
                    out.append(f.name()).append(" { ").append(inner).append("} ");
                    count++;
                }
            }
        }
        seen.remove(d.name());
        return count;
    }

    // ── variable types ─────────────────────────────────────────────────────────────────────────────────

    private Schema schemaOf(String type, Set<String> inProgress) {
        String t = type.trim();
        if (t.endsWith("!")) {
            t = t.substring(0, t.length() - 1);
        }
        if (t.startsWith("[")) {
            return new ArraySchema(schemaOf(t.substring(1, t.lastIndexOf(']')), inProgress), null, null);
        }
        return switch (t) {
            case "String" -> ScalarSchema.of(ScalarType.STRING, null);
            case "ID" -> ScalarSchema.of(ScalarType.STRING, null);
            case "Int", "Long", "BigInteger", "Short", "Byte" -> ScalarSchema.of(ScalarType.INTEGER, t.equals("Int") ? "int32" : "int64");
            case "Float", "BigDecimal", "Decimal" -> ScalarSchema.of(ScalarType.NUMBER, "double");
            case "Boolean" -> ScalarSchema.of(ScalarType.BOOLEAN, null);
            case "Date", "LocalDate" -> ScalarSchema.of(ScalarType.STRING, "date");
            case "DateTime", "LocalDateTime", "OffsetDateTime", "Instant" -> ScalarSchema.of(ScalarType.STRING, "date-time");
            case "UUID" -> ScalarSchema.of(ScalarType.STRING, "uuid");
            case "Time", "LocalTime" -> ScalarSchema.of(ScalarType.STRING, "time");
            case "Url", "URL", "URI" -> ScalarSchema.of(ScalarType.STRING, "uri");
            case "Email" -> ScalarSchema.of(ScalarType.STRING, "email");
            default -> custom(t, inProgress);
        };
    }

    private Schema custom(String name, Set<String> inProgress) {
        Definition d = types.get(name);
        if (d == null || d.kind().equals("scalar")) {
            return ObjectSchema.freeFormObject(); // JSON, Object, an unknown scalar
        }
        if (d.kind().equals("enum")) {
            return new ScalarSchema(ScalarType.STRING, null, Constraints.NONE,
                    d.values(), null);
        }
        if (!d.kind().equals("input") || !inProgress.add(name)) {
            return ObjectSchema.freeFormObject();
        }
        try {
            Map<String, Property> props = new LinkedHashMap<>();
            for (Field f : d.fields()) {
                props.put(f.name(), new Property(schemaOf(f.type(), inProgress), f.type().endsWith("!"), false, null));
            }
            return new ObjectSchema(props, false);
        } finally {
            inProgress.remove(name);
        }
    }
}
