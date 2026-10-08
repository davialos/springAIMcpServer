package com.springaimcpservercommon.celfaker.payload;

import com.springaimcpservercommon.ruleengine.model.DataType;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Walks a JSON payload and lists every value that can be a parameter: the sys object is the enclosing object
 * (nested objects are joined with {@code _}; top-level scalars belong to the root object), the attribute is the
 * property name, the CEL type is inferred from the value.
 */
public final class PayloadAnalyzer {

    /** Words the CEL grammar reserves; an attribute with one of these names gets a trailing underscore. */
    private static final Set<String> RESERVED = Set.of("true", "false", "null", "in", "as", "break", "const",
            "continue", "else", "for", "function", "if", "import", "let", "loop", "package", "namespace", "return",
            "var", "void", "while");
    private static final Pattern ISO_INSTANT =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:\\d{2})$");

    /**
     * Result of an analysis.
     *
     * @param candidates parameter candidates in document order
     * @param skipped    human-readable reasons for values that cannot be parameters (arrays of objects, collisions)
     */
    public record Analysis(List<Candidate> candidates, List<String> skipped) {
    }

    private PayloadAnalyzer() {
    }

    /**
     * Analyses a payload.
     *
     * @param payload    the JSON document (an object)
     * @param rootObject sys object code for top-level scalars
     * @param mapPaths   paths to treat as a MAP value instead of descending into them
     * @return the candidates
     */
    public static Analysis analyze(JsonNode payload, String rootObject, Set<String> mapPaths) {
        List<Candidate> out = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        if (payload.isObject()) {
            walk(payload, "", identifier(rootObject), rootObject, mapPaths, out, skipped, names);
        } else {
            skipped.add("payload is not a JSON object");
        }
        return new Analysis(out, skipped);
    }

    /**
     * Analyses a payload with no MAP overrides.
     *
     * @param payload    the JSON document
     * @param rootObject sys object code for top-level scalars
     * @return the candidates
     */
    public static Analysis analyze(JsonNode payload, String rootObject) {
        return analyze(payload, rootObject, Set.of());
    }

    private static void walk(JsonNode node, String prefix, String objectCode, String rootObject, Set<String> mapPaths,
                             List<Candidate> out, List<String> skipped, Set<String> names) {
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            JsonNode v = e.getValue();
            if (v.isObject() && !v.isEmpty() && !mapPaths.contains(path)) {
                String childObject = prefix.isEmpty() ? identifier(e.getKey()) : objectCode + "_" + identifier(e.getKey());
                walk(v, path, childObject, rootObject, mapPaths, out, skipped, names);
                continue;
            }
            DataType type = infer(v, mapPaths.contains(path));
            if (type == null) {
                skipped.add(path + ": array of objects or mixed types cannot be a parameter");
                continue;
            }
            JsonNode sample = type == DataType.TIMESTAMP && v.isString() ? JsonValues.toNode(instant(v.stringValue())) : v;
            Candidate c = new Candidate(path, objectCode, attribute(e.getKey()), type, sample);
            if (!names.add(c.celName())) {
                skipped.add(path + ": name collision on " + c.celName());
                continue;
            }
            out.add(c);
        }
    }

    private static DataType infer(JsonNode v, boolean forceMap) {
        if (forceMap || v.isObject()) {
            return DataType.MAP;
        }
        if (v.isNull() || v.isMissingNode()) {
            return DataType.ANY;
        }
        if (v.isBoolean()) {
            return DataType.BOOL;
        }
        if (v.isIntegralNumber()) {
            return JsonValues.fitsLong(v.bigIntegerValue()) ? DataType.INT : DataType.DOUBLE;
        }
        if (v.isNumber()) {
            return DataType.DOUBLE;
        }
        if (v.isString()) {
            String s = v.stringValue();
            if (instant(s) != null) {
                return DataType.TIMESTAMP;
            }
            return duration(s) ? DataType.DURATION : DataType.STRING;
        }
        if (v.isArray()) {
            if (v.isEmpty()) {
                return DataType.ANY;
            }
            boolean strings = true;
            boolean ints = true;
            boolean numbers = true;
            for (JsonNode e : v) {
                strings &= e.isString();
                ints &= e.isIntegralNumber() && JsonValues.fitsLong(e.bigIntegerValue());
                numbers &= e.isNumber();
            }
            return strings ? DataType.LIST_STRING : ints ? DataType.LIST_INT : numbers ? DataType.LIST_DOUBLE : null;
        }
        return null;
    }

    private static String instant(String s) {
        if (!ISO_INSTANT.matcher(s).matches()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(s).toInstant().toString();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean duration(String s) {
        if (s.length() < 3 || !(s.startsWith("P") || s.startsWith("-P") || s.startsWith("PT"))) {
            return false;
        }
        try {
            Duration.parse(s);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /**
     * Turns any text into a CEL identifier segment.
     *
     * @param raw the raw name
     * @return letters, digits and underscores, not starting with a digit
     */
    public static String identifier(String raw) {
        String s = raw.replaceAll("[^A-Za-z0-9_]", "_");
        if (s.isEmpty()) {
            return "_";
        }
        return Character.isDigit(s.charAt(0)) ? "_" + s : s;
    }

    private static String attribute(String raw) {
        String id = identifier(raw);
        return RESERVED.contains(id.toLowerCase(Locale.ROOT)) ? id + "_" : id;
    }
}
