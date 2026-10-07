package com.springaimcpservercommon.loadtest.discovery;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The response-validation schema dialect and its builders. Request schemas ({@code Schema}) describe what a client
 * may <em>send</em>; this is what a successful response must <em>look like</em>, checked by {@code lib/validate.js}
 * under load: a JSON-Schema subset — {@code type} (a name or a list of names), {@code properties}, {@code required},
 * {@code items}, {@code enum} and {@code nullable} — where {@code {}} accepts anything. It is deliberately lenient:
 * extra properties are fine, unknown shapes are {@code {}}, and only declared types and declared required members
 * can fail, so a check that fires points at a real contract break rather than at the generator's guesswork.
 */
public final class ResponseSchemas {

    /** Nesting deeper than this is not validated ({@code {}}), which also ends recursive types. */
    static final int MAX_DEPTH = 6;

    private ResponseSchemas() {
    }

    /** @return the schema that accepts any value */
    static ObjectNode any() {
        return Documents.json().createObjectNode();
    }

    /**
     * A scalar schema.
     *
     * @param types JSON types accepted ({@code integer}, {@code number}, {@code string}, {@code boolean})
     * @return the schema
     */
    static ObjectNode scalar(String... types) {
        ObjectNode n = any();
        if (types.length == 1) {
            n.put("type", types[0]);
        } else {
            ArrayNode a = n.putArray("type");
            for (String t : types) {
                a.add(t);
            }
        }
        return n;
    }

    /**
     * An object schema with no members yet.
     *
     * @return the schema
     */
    static ObjectNode object() {
        ObjectNode n = any();
        n.put("type", "object");
        return n;
    }

    /**
     * An array schema.
     *
     * @param items schema of the elements
     * @return the schema
     */
    static ObjectNode array(JsonNode items) {
        ObjectNode n = any();
        n.put("type", "array");
        n.set("items", items);
        return n;
    }

    /**
     * Declares a member of an object schema.
     *
     * @param object   an {@link #object()} schema
     * @param name     JSON member name
     * @param schema   member schema
     * @param required whether every response must carry it
     */
    static void property(ObjectNode object, String name, JsonNode schema, boolean required) {
        ObjectNode props = object.has("properties") ? (ObjectNode) object.get("properties")
                : object.putObject("properties");
        props.set(name, schema);
        if (required) {
            ArrayNode req = object.has("required") ? (ArrayNode) object.get("required") : object.putArray("required");
            boolean present = false;
            for (JsonNode r : req) {
                present |= r.asString().equals(name);
            }
            if (!present) {
                req.add(name);
            }
        }
    }

    /**
     * Whether a schema says nothing ({@code {}}), so it can be dropped from a catalog.
     *
     * @param schema a schema, or {@code null}
     * @return {@code true} for {@code null} and the empty schema
     */
    public static boolean isEmpty(@Nullable JsonNode schema) {
        return schema == null || schema.isNull() || schema.isEmpty();
    }

    // ── inference from recorded responses ──────────────────────────────────────────────────────────────

    /**
     * Infers a schema from one recorded response body.
     *
     * @param sample parsed JSON
     * @return the schema
     */
    public static ObjectNode infer(JsonNode sample) {
        return infer(sample, 0);
    }

    private static ObjectNode infer(JsonNode v, int depth) {
        if (depth >= MAX_DEPTH) {
            return any();
        }
        if (v.isNull() || v.isMissingNode()) {
            ObjectNode n = any();
            n.put("nullable", true);
            return n;
        }
        if (v.isObject()) {
            ObjectNode o = object();
            for (var e : v.properties()) {
                property(o, e.getKey(), infer(e.getValue(), depth + 1), !e.getValue().isNull());
            }
            return o;
        }
        if (v.isArray()) {
            ObjectNode items = null;
            for (JsonNode x : v) {
                ObjectNode s = infer(x, depth + 1);
                items = items == null ? s : merge(items, s);
            }
            return array(items == null ? any() : items);
        }
        if (v.isIntegralNumber()) {
            return scalar("integer");
        }
        if (v.isNumber()) {
            return scalar("number");
        }
        return scalar(v.isBoolean() ? "boolean" : "string");
    }

    /**
     * Merges what two recorded responses of one operation show: a member is required only when both had it, an
     * integer and a number become a number, and a conflict falls back to "anything".
     *
     * @param a first schema
     * @param b second schema
     * @return the merged schema
     */
    public static ObjectNode merge(JsonNode a, JsonNode b) {
        if (nullOnly(a)) {
            return nullable(b);
        }
        if (nullOnly(b)) {
            return nullable(a);
        }
        if (a.isEmpty() || b.isEmpty()) {
            ObjectNode n = any();
            if (a.path("nullable").asBoolean(false) || b.path("nullable").asBoolean(false)) {
                n.put("nullable", true);
            }
            return n;
        }
        String ta = a.path("type").asString("");
        String tb = b.path("type").asString("");
        boolean nullable = a.path("nullable").asBoolean(false) || b.path("nullable").asBoolean(false);
        ObjectNode out;
        if (ta.equals("object") && tb.equals("object")) {
            out = object();
            Set<String> names = new LinkedHashSet<>();
            a.path("properties").propertyNames().forEach(names::add);
            b.path("properties").propertyNames().forEach(names::add);
            for (String name : names) {
                JsonNode pa = a.path("properties").path(name);
                JsonNode pb = b.path("properties").path(name);
                boolean both = !pa.isMissingNode() && !pb.isMissingNode();
                JsonNode merged = both ? merge(pa, pb) : (pa.isMissingNode() ? pb : pa);
                property(out, name, merged, both && requires(a, name) && requires(b, name));
            }
        } else if (ta.equals("array") && tb.equals("array")) {
            out = array(merge(a.path("items"), b.path("items")));
        } else if (ta.equals(tb) && !ta.isEmpty()) {
            out = scalar(ta);
        } else if (isNumeric(ta) && isNumeric(tb)) {
            out = scalar("number");
        } else {
            out = any();
        }
        if (nullable) {
            out.put("nullable", true);
        }
        return out;
    }

    /** What a recorded {@code null} shows: nothing but that the member can be null. */
    private static boolean nullOnly(JsonNode schema) {
        return !schema.has("type") && schema.path("nullable").asBoolean(false) && schema.size() == 1;
    }

    private static ObjectNode nullable(JsonNode schema) {
        ObjectNode copy = ((ObjectNode) schema).deepCopy();
        copy.put("nullable", true);
        return copy;
    }

    private static boolean isNumeric(String t) {
        return t.equals("integer") || t.equals("number");
    }

    private static boolean requires(JsonNode object, String name) {
        for (JsonNode r : object.path("required")) {
            if (r.asString().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Names of the members a schema declares required.
     *
     * @param schema an object schema
     * @return names, possibly empty
     */
    public static List<String> required(JsonNode schema) {
        List<String> out = new ArrayList<>();
        schema.path("required").forEach(r -> out.add(r.asString()));
        return out;
    }
}
