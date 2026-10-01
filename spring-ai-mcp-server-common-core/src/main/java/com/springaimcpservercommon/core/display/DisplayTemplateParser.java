package com.springaimcpservercommon.core.display;

import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict parser for display templates (LLD-06 §8.3). Unknown properties, wrong types and out-of-range values are
 * errors, all reported at once with their JSON location, so a template is rejected when the agent is loaded rather
 * than silently showing less (or more) than intended. Uses a private Jackson 3 mapper; never the host's (ADR-0019).
 *
 * <pre>{@code
 * {"version": 1, "blocks": [
 *   {"type": "text", "title": "Summary"},
 *   {"type": "fields", "title": "Customer", "source": "customer",
 *    "fields": [{"path": "name", "label": "Name"}, {"path": "cardNumber", "label": "Card", "mask": "partial"}]},
 *   {"type": "table", "title": "Orders", "source": "orders", "maxRows": 20,
 *    "columns": [{"path": "id", "label": "Order #"}, {"path": "total", "label": "Total", "format": "number"}]},
 *   {"type": "section", "title": "History", "blocks": [ ... ]}]}
 * }</pre>
 *
 * <p>Limits: at most {@value #MAX_NODES} nodes, sections nested {@value #MAX_DEPTH} deep, {@value #MAX_FIELDS}
 * fields or columns per block, labels and titles of {@value #MAX_LABEL} characters, {@code maxRows} 1–
 * {@value #MAX_ROWS} (default {@value #DEFAULT_ROWS}). Thread-safe.
 */
public final class DisplayTemplateParser {

    /** Most nodes in a template. */
    public static final int MAX_NODES = 100;
    /** Deepest section nesting. */
    public static final int MAX_DEPTH = 4;
    /** Most fields or columns per block. */
    public static final int MAX_FIELDS = 50;
    /** Longest title or label. */
    public static final int MAX_LABEL = 128;
    /** Largest {@code maxRows}. */
    public static final int MAX_ROWS = 1_000;
    /** Default {@code maxRows}. */
    public static final int DEFAULT_ROWS = 100;

    private static final Pattern PATH = Pattern.compile("\\$|[A-Za-z0-9_-]{1,64}(\\.[A-Za-z0-9_-]{1,64}){0,15}");

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "text", Set.of("type", "title"),
            "fields", Set.of("type", "title", "source", "fields"),
            "table", Set.of("type", "title", "source", "columns", "maxRows"),
            "section", Set.of("type", "title", "blocks"));
    private static final Set<String> FIELD_KEYS = Set.of("path", "label", "format", "mask");

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /**
     * Parses a template from JSON text.
     *
     * @param json template JSON
     * @return the template
     * @throws DisplayTemplateException with every problem found
     */
    public DisplayTemplate parse(String json) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JacksonException e) {
            throw new DisplayTemplateException(List.of("$: not valid JSON (" + e.getOriginalMessage() + ")"));
        }
        return parse(root);
    }

    /**
     * Parses a template from an already parsed JSON tree.
     *
     * @param root template JSON tree
     * @return the template
     * @throws DisplayTemplateException with every problem found
     */
    public DisplayTemplate parse(JsonNode root) {
        List<String> errors = new ArrayList<>();
        if (root == null || !root.isObject()) {
            throw new DisplayTemplateException(List.of("$: must be an object"));
        }
        for (String key : names(root)) {
            if (!key.equals("version") && !key.equals("blocks")) {
                errors.add("$." + key + ": unknown property");
            }
        }
        JsonNode version = root.get("version");
        if (version == null || !version.isInt() || version.intValue() != DisplayTemplate.VERSION) {
            errors.add("$.version: must be " + DisplayTemplate.VERSION);
        }
        int[] nodes = {0};
        List<DisplayNode> blocks = nodes(root.get("blocks"), "$.blocks", 0, nodes, errors);
        if (nodes[0] > MAX_NODES) {
            errors.add("$: more than " + MAX_NODES + " nodes");
        }
        if (!errors.isEmpty()) {
            throw new DisplayTemplateException(errors);
        }
        return new DisplayTemplate(DisplayTemplate.VERSION, blocks);
    }

    private List<DisplayNode> nodes(@Nullable JsonNode array, String at, int depth, int[] count, List<String> errors) {
        List<DisplayNode> out = new ArrayList<>();
        if (array == null || !array.isArray() || array.isEmpty()) {
            errors.add(at + ": must be a non-empty array");
            return out;
        }
        for (int i = 0; i < array.size(); i++) {
            count[0]++;
            DisplayNode node = node(array.get(i), at + "[" + i + "]", depth, count, errors);
            if (node != null) {
                out.add(node);
            }
        }
        return out;
    }

    private @Nullable DisplayNode node(JsonNode n, String at, int depth, int[] count, List<String> errors) {
        if (!n.isObject()) {
            errors.add(at + ": must be an object");
            return null;
        }
        String type = text(n, "type", at, errors, true);
        if (type == null) {
            return null;
        }
        Set<String> allowed = ALLOWED.get(type);
        if (allowed == null) {
            errors.add(at + ".type: must be one of text, fields, table, section");
            return null;
        }
        for (String key : names(n)) {
            if (!allowed.contains(key)) {
                errors.add(at + "." + key + ": unknown property for type " + type);
            }
        }
        String title = label(n, "title", at, errors, type.equals("section"));
        String source = path(n, "source", at, errors, false);
        return switch (type) {
            case "text" -> new DisplayNode.Text(title);
            case "fields" -> new DisplayNode.Fields(title, source, fields(n.get("fields"), at + ".fields", errors));
            case "table" -> new DisplayNode.Table(title, source, fields(n.get("columns"), at + ".columns", errors),
                    maxRows(n, at, errors));
            default -> {
                if (depth + 1 > MAX_DEPTH) {
                    errors.add(at + ": sections nested deeper than " + MAX_DEPTH);
                    yield null;
                }
                List<DisplayNode> children = nodes(n.get("blocks"), at + ".blocks", depth + 1, count, errors);
                yield title == null ? null : new DisplayNode.Section(title, children);
            }
        };
    }

    private List<FieldSpec> fields(@Nullable JsonNode array, String at, List<String> errors) {
        List<FieldSpec> out = new ArrayList<>();
        if (array == null || !array.isArray() || array.isEmpty()) {
            errors.add(at + ": must be a non-empty array");
            return out;
        }
        if (array.size() > MAX_FIELDS) {
            errors.add(at + ": more than " + MAX_FIELDS + " entries");
        }
        for (int i = 0; i < array.size(); i++) {
            JsonNode f = array.get(i);
            String fat = at + "[" + i + "]";
            if (!f.isObject()) {
                errors.add(fat + ": must be an object");
                continue;
            }
            for (String key : names(f)) {
                if (!FIELD_KEYS.contains(key)) {
                    errors.add(fat + "." + key + ": unknown property");
                }
            }
            String path = path(f, "path", fat, errors, true);
            String label = label(f, "label", fat, errors, false);
            DisplayFormat format = constant(f, "format", fat, DisplayFormat.class, DisplayFormat.TEXT, errors);
            DisplayMask mask = constant(f, "mask", fat, DisplayMask.class, DisplayMask.NONE, errors);
            if (path != null) {
                out.add(new FieldSpec(path, label != null ? label : humanize(path), format, mask));
            }
        }
        return out;
    }

    private static int maxRows(JsonNode n, String at, List<String> errors) {
        JsonNode v = n.get("maxRows");
        if (v == null) {
            return DEFAULT_ROWS;
        }
        if (!v.isInt() || v.intValue() < 1 || v.intValue() > MAX_ROWS) {
            errors.add(at + ".maxRows: must be an integer between 1 and " + MAX_ROWS);
            return DEFAULT_ROWS;
        }
        return v.intValue();
    }

    private static @Nullable String text(JsonNode n, String key, String at, List<String> errors, boolean required) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) {
            if (required) {
                errors.add(at + "." + key + ": is required");
            }
            return null;
        }
        if (!v.isString()) {
            errors.add(at + "." + key + ": must be a string");
            return null;
        }
        return v.stringValue();
    }

    private static @Nullable String label(JsonNode n, String key, String at, List<String> errors, boolean required) {
        String s = text(n, key, at, errors, required);
        if (s != null && (s.isBlank() || s.length() > MAX_LABEL)) {
            errors.add(at + "." + key + ": must be 1–" + MAX_LABEL + " characters");
            return null;
        }
        return s;
    }

    private static @Nullable String path(JsonNode n, String key, String at, List<String> errors, boolean required) {
        String s = text(n, key, at, errors, required);
        if (s != null && !PATH.matcher(s).matches()) {
            errors.add(at + "." + key + ": must be $ or a dot path of letters, digits, '_' and '-'");
            return null;
        }
        return s;
    }

    private static <E extends Enum<E>> E constant(JsonNode n, String key, String at, Class<E> type, E fallback,
                                                   List<String> errors) {
        String s = text(n, key, at, errors, false);
        if (s == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add(at + "." + key + ": unknown value '" + s + "'");
            return fallback;
        }
    }

    private static List<String> names(JsonNode n) {
        return n.properties().stream().map(Map.Entry::getKey).toList();
    }

    /**
     * Turns a key or path into a label: last segment, camelCase and snake_case split, first letter upper case
     * ({@code customer.firstName} → {@code First name}).
     *
     * @param path key or dot path
     * @return the label
     */
    public static String humanize(String path) {
        if (path.equals("$")) {
            return "Value";
        }
        String last = path.substring(path.lastIndexOf('.') + 1);
        String spaced = last.replaceAll("(?<=\\p{Ll})(?=\\p{Lu})", " ").replace('_', ' ').replace('-', ' ').strip()
                .toLowerCase(Locale.ROOT);
        if (spaced.isEmpty()) {
            return last;
        }
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
