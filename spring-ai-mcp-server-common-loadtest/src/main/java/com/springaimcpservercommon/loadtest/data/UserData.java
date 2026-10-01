package com.springaimcpservercommon.loadtest.data;

import com.springaimcpservercommon.loadtest.discovery.Documents;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Values supplied by the user: per-field value lists, whole request bodies per API and explicit real-data
 * bindings. Becomes the suite's {@code data/user.json}:
 * <pre>{@code
 * {
 *   "fields":   { "email": ["qa1@example.test"], "createOrder.body.customerId": [101, 102] },
 *   "payloads": { "createOrder": [ { "customerId": 101, "items": [ { "sku": "A-1", "quantity": 2 } ] } ] },
 *   "bindings": { "*.customerId": "customers.id" }
 * }
 * }</pre>
 * Field keys may be a full {@link FieldKeys} key, {@code <apiId|SchemaName>.<field>} or a bare field name.
 *
 * @param fields   field key → values
 * @param payloads API id → complete request bodies
 * @param bindings field key → {@code [schema.]table.column}
 */
public record UserData(Map<String, List<Object>> fields, Map<String, List<Object>> payloads,
                       Map<String, String> bindings) {

    /** Compact constructor: ordered, unmodifiable copies. */
    public UserData {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        payloads = Collections.unmodifiableMap(new LinkedHashMap<>(payloads));
        bindings = Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
    }

    /**
     * No user data.
     *
     * @return empty data
     */
    public static UserData empty() {
        return new UserData(Map.of(), Map.of(), Map.of());
    }

    /**
     * Loads a JSON/YAML document (format above) or a CSV file whose header names field keys and whose rows hold
     * values (empty cells skipped).
     *
     * @param file file to load
     * @return the data
     */
    public static UserData load(Path file) {
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        return file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".csv")
                ? csv(text) : json(Documents.parse(text));
    }

    private static UserData json(JsonNode root) {
        Map<String, List<Object>> fields = new LinkedHashMap<>();
        for (var e : root.path("fields").properties()) {
            fields.put(e.getKey(), values(e.getValue()));
        }
        Map<String, List<Object>> payloads = new LinkedHashMap<>();
        for (var e : root.path("payloads").properties()) {
            List<Object> list = new ArrayList<>();
            if (e.getValue().isArray()) {
                e.getValue().forEach(list::add);
            } else {
                list.add(e.getValue());
            }
            payloads.put(e.getKey(), list);
        }
        Map<String, String> bindings = new LinkedHashMap<>();
        for (var e : root.path("bindings").properties()) {
            bindings.put(e.getKey(), e.getValue().asString());
        }
        return new UserData(fields, payloads, bindings);
    }

    private static List<Object> values(JsonNode node) {
        List<Object> out = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(v -> out.add(scalar(v)));
        } else {
            out.add(scalar(node));
        }
        return out;
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
        return v.isContainer() ? v : v.asString();
    }

    private static UserData csv(String text) {
        List<List<String>> rows = Csv.parse(text);
        Map<String, List<Object>> fields = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            return empty();
        }
        List<String> header = rows.getFirst();
        for (String h : header) {
            fields.put(h.trim(), new ArrayList<>());
        }
        for (List<String> row : rows.subList(1, rows.size())) {
            for (int i = 0; i < header.size() && i < row.size(); i++) {
                String cell = row.get(i).trim();
                if (!cell.isEmpty()) {
                    fields.get(header.get(i).trim()).add(Csv.typed(cell));
                }
            }
        }
        fields.values().removeIf(List::isEmpty);
        return new UserData(fields, Map.of(), Map.of());
    }

    /**
     * Combines two data sets; list values are concatenated without duplicates, later bindings win.
     *
     * @param other data to add
     * @return the union
     */
    public UserData merge(UserData other) {
        Map<String, List<Object>> f = new LinkedHashMap<>();
        fields.forEach((k, v) -> f.put(k, new ArrayList<>(v)));
        other.fields.forEach((k, v) -> addAbsent(f.computeIfAbsent(k, x -> new ArrayList<>()), v));
        Map<String, List<Object>> p = new LinkedHashMap<>();
        payloads.forEach((k, v) -> p.put(k, new ArrayList<>(v)));
        other.payloads.forEach((k, v) -> addAbsent(p.computeIfAbsent(k, x -> new ArrayList<>()), v));
        Map<String, String> b = new LinkedHashMap<>(bindings);
        b.putAll(other.bindings);
        return new UserData(f, p, b);
    }

    private static void addAbsent(List<Object> target, List<Object> values) {
        for (Object v : values) {
            if (!target.contains(v)) {
                target.add(v);
            }
        }
    }

    /**
     * Copy with one field's values replaced.
     *
     * @param key    field key
     * @param values values
     * @return the copy
     */
    public UserData withField(String key, List<Object> values) {
        Map<String, List<Object>> f = new LinkedHashMap<>(fields);
        f.put(key, List.copyOf(values));
        return new UserData(f, payloads, bindings);
    }

    /**
     * The values for a field key, trying {@code key}, then {@code owner.name}, then {@code name}.
     *
     * @param key   field key
     * @param owner API id or schema name
     * @return values, empty if none
     */
    public List<Object> valuesFor(String key, String owner) {
        String name = FieldKeys.name(key);
        for (String k : List.of(key, owner + "." + name, name)) {
            List<Object> v = fields.get(k);
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return List.of();
    }

    /**
     * Renders {@code data/user.json}.
     *
     * @return JSON tree
     */
    public ObjectNode toJson() {
        ObjectNode root = Documents.json().createObjectNode();
        root.set("fields", Documents.json().valueToTree(fields));
        root.set("payloads", Documents.json().valueToTree(payloads));
        root.set("bindings", Documents.json().valueToTree(bindings));
        return root;
    }
}
