package com.springaimcpservercommon.celfaker.importer;

import com.springaimcpservercommon.celfaker.payload.JsonValues;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.Locale;
import java.util.Map;

/**
 * Builds an example value for an OpenAPI / JSON schema: the declared {@code example}, {@code default} or first
 * {@code enum} value wins, otherwise a value that satisfies type, format and numeric/length bounds. Local
 * {@code $ref}s are resolved against the document; recursion is cut at a fixed depth.
 */
final class SchemaExamples {

    private static final int MAX_DEPTH = 8;
    private final JsonNode doc;

    SchemaExamples(JsonNode doc) {
        this.doc = doc;
    }

    /** Resolves a local {@code $ref} chain (e.g. {@code #/components/schemas/Order}). */
    JsonNode resolve(JsonNode node) {
        JsonNode cur = node;
        for (int i = 0; i < MAX_DEPTH && cur != null && cur.has("$ref"); i++) {
            String ref = cur.path("$ref").asString();
            JsonNode target = ref.startsWith("#/") ? doc.at(ref.substring(1)) : null;
            if (target == null || target.isMissingNode()) {
                return JsonNodeFactory.instance.objectNode();
            }
            cur = target;
        }
        return cur == null ? JsonNodeFactory.instance.objectNode() : cur;
    }

    JsonNode example(JsonNode schema, String name) {
        return example(schema, name, 0);
    }

    private JsonNode example(JsonNode raw, String name, int depth) {
        JsonNode s = resolve(raw);
        if (depth > MAX_DEPTH) {
            return JsonNodeFactory.instance.nullNode();
        }
        if (s.has("example") && !s.path("example").isNull()) {
            return s.path("example");
        }
        if (s.has("default") && !s.path("default").isNull()) {
            return s.path("default");
        }
        if (s.path("enum").isArray() && !s.path("enum").isEmpty()) {
            return s.path("enum").get(0);
        }
        if (s.path("const").isMissingNode() == false && !s.path("const").isNull()) {
            return s.path("const");
        }
        for (String combo : new String[]{"allOf"}) {
            if (s.path(combo).isArray()) {
                ObjectNode merged = JsonNodeFactory.instance.objectNode();
                for (JsonNode part : s.path(combo)) {
                    JsonNode e = example(part, name, depth + 1);
                    if (e.isObject()) {
                        merged.setAll((ObjectNode) e);
                    }
                }
                if (s.path("properties").isObject()) {
                    merged.setAll((ObjectNode) example(withoutAllOf(s), name, depth + 1));
                }
                return merged;
            }
        }
        for (String combo : new String[]{"oneOf", "anyOf"}) {
            if (s.path(combo).isArray() && !s.path(combo).isEmpty()) {
                return example(s.path(combo).get(0), name, depth + 1);
            }
        }
        String type = s.path("type").isString() ? s.path("type").stringValue()
                : s.path("type").isArray() && !s.path("type").isEmpty() ? firstNonNull(s.path("type"))
                : s.has("properties") ? "object" : s.has("items") ? "array" : "string";
        return switch (type) {
            case "object" -> object(s, depth);
            case "array" -> array(s, name, depth);
            case "integer" -> JsonNodeFactory.instance.numberNode(integer(s));
            case "number" -> JsonNodeFactory.instance.numberNode(number(s));
            case "boolean" -> JsonNodeFactory.instance.booleanNode(true);
            default -> JsonNodeFactory.instance.stringNode(string(s, name));
        };
    }

    private static String firstNonNull(JsonNode types) {
        for (JsonNode t : types) {
            if (t.isString() && !t.stringValue().equals("null")) {
                return t.stringValue();
            }
        }
        return "string";
    }

    private static JsonNode withoutAllOf(JsonNode s) {
        ObjectNode copy = ((ObjectNode) s).deepCopy();
        copy.remove("allOf");
        return copy;
    }

    private JsonNode object(JsonNode s, int depth) {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        for (Map.Entry<String, JsonNode> p : s.path("properties").properties()) {
            JsonNode prop = resolve(p.getValue());
            if (prop.path("readOnly").asBoolean(false)) {
                continue;
            }
            o.set(p.getKey(), example(p.getValue(), p.getKey(), depth + 1));
        }
        return o;
    }

    private JsonNode array(JsonNode s, String name, int depth) {
        ArrayNode a = JsonNodeFactory.instance.arrayNode();
        int n = Math.max(1, Math.min(2, s.path("minItems").asInt(1)));
        for (int i = 0; i < n; i++) {
            a.add(example(s.path("items"), name, depth + 1));
        }
        return a;
    }

    private static long integer(JsonNode s) {
        long min = s.has("minimum") ? (long) Math.ceil(s.path("minimum").asDouble()) + (s.path("exclusiveMinimum").asBoolean(false) ? 1 : 0) : Long.MIN_VALUE;
        long max = s.has("maximum") ? (long) Math.floor(s.path("maximum").asDouble()) - (s.path("exclusiveMaximum").asBoolean(false) ? 1 : 0) : Long.MAX_VALUE;
        if (s.path("exclusiveMinimum").isNumber()) {
            min = (long) Math.floor(s.path("exclusiveMinimum").asDouble()) + 1;
        }
        if (s.path("exclusiveMaximum").isNumber()) {
            max = (long) Math.ceil(s.path("exclusiveMaximum").asDouble()) - 1;
        }
        long pick = 10;
        if (min != Long.MIN_VALUE && max != Long.MAX_VALUE) {
            pick = min + (max - min) / 2;
        } else if (min != Long.MIN_VALUE) {
            pick = Math.max(min, 10);
        } else if (max != Long.MAX_VALUE) {
            pick = Math.min(max, 10);
        }
        return pick;
    }

    private static double number(JsonNode s) {
        boolean hasMin = s.path("minimum").isNumber() || s.path("exclusiveMinimum").isNumber();
        boolean hasMax = s.path("maximum").isNumber() || s.path("exclusiveMaximum").isNumber();
        double min = s.path("minimum").isNumber() ? s.path("minimum").asDouble() : s.path("exclusiveMinimum").asDouble(0);
        double max = s.path("maximum").isNumber() ? s.path("maximum").asDouble() : s.path("exclusiveMaximum").asDouble(0);
        if (hasMin && hasMax) {
            return Math.round((min + (max - min) / 2) * 100) / 100.0;
        }
        if (hasMin) {
            return Math.max(min + 0.5, 9.99);
        }
        if (hasMax) {
            return Math.min(max - 0.5, 9.99);
        }
        return 9.99;
    }

    private static String string(JsonNode s, String name) {
        String format = s.path("format").asString("").toLowerCase(Locale.ROOT);
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String v = switch (format) {
            case "email" -> "ada.lovelace@example.com";
            case "uuid" -> "3fa85f64-5717-4562-b3fc-2c963f66afa6";
            case "date" -> "2025-03-04";
            case "date-time" -> "2025-03-04T10:15:30Z";
            case "time" -> "10:15:30";
            case "duration" -> "PT2H";
            case "uri", "url" -> "https://example.com/resource";
            case "hostname" -> "example.com";
            case "ipv4" -> "192.0.2.10";
            case "ipv6" -> "2001:db8::1";
            case "byte" -> "c2FtcGxl";
            case "password" -> "S3cret-pass!";
            default -> n.contains("email") ? "ada.lovelace@example.com" : n.contains("name") ? "Ada"
                    : n.contains("phone") ? "+15550100" : n.contains("city") ? "London"
                    : n.contains("currency") ? "EUR" : n.contains("country") ? "GB" : "sample";
        };
        int min = s.path("minLength").asInt(0);
        int max = s.path("maxLength").asInt(Integer.MAX_VALUE);
        StringBuilder sb = new StringBuilder(v);
        while (sb.length() < min) {
            sb.append('x');
        }
        return sb.length() > max ? sb.substring(0, max) : sb.toString();
    }

    static JsonNode copy(JsonNode n) {
        return JsonValues.MAPPER.valueToTree(n);
    }
}
