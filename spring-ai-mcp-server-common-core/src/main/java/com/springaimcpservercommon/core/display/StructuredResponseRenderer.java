package com.springaimcpservercommon.core.display;

import com.springaimcpservercommon.core.guard.PiiRedactor;
import com.springaimcpservercommon.core.guard.PiiType;
import com.springaimcpservercommon.core.json.CanonicalJson;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns an agent answer into a {@link StructuredResponse} (LLD-06 §8.3).
 *
 * <p><b>With a {@link DisplayTemplate}</b> only the values the template names are shown, in its order, with its
 * labels, formats and masks: the template is an allow-list, so data the model returned but the backend did not
 * choose to display never reaches the client. <b>Without a template</b> the answer is laid out automatically: prose
 * becomes a text block, a JSON object becomes a field block (nested objects become further blocks up to three
 * levels), an array of objects becomes a table.
 *
 * <p>Whatever the layout, every value passes the same pipeline: keys that are {@link SensitiveFields sensitive} are
 * fully masked (a template can add masking, never remove it), then template masks apply, then every string and
 * number is checked by the {@link PiiRedactor} and personal data is replaced by typed placeholders. Labels derived
 * from data keys are redacted too. The response reports how many values were redacted and masked, never which.
 *
 * <p>Thread-safe; state lives in a per-call context.
 */
public final class StructuredResponseRenderer {

    /** Replaces a masked value. */
    public static final String MASK = "••••••";
    /** Most blocks in one response. */
    public static final int MAX_BLOCKS = 50;
    /** Rows of an automatically laid-out table. */
    public static final int AUTO_MAX_ROWS = 100;
    /** Columns of an automatically laid-out table. */
    public static final int AUTO_MAX_COLUMNS = 20;
    /** Nesting followed by the automatic layout. */
    public static final int AUTO_MAX_DEPTH = 3;
    /** Elements of a scalar list shown in one cell. */
    private static final int MAX_LIST_ELEMENTS = 20;

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern DATETIME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}.*");

    private final PiiRedactor redactor;

    /**
     * Creates the renderer.
     *
     * @param redactor redactor applied to every displayed string and number
     */
    public StructuredResponseRenderer(PiiRedactor redactor) {
        this.redactor = Objects.requireNonNull(redactor, "redactor");
    }

    /** Per-call counters and settings. */
    private static final class Ctx {
        final SensitiveFields sensitive;
        final Map<PiiType, Integer> counts = new EnumMap<>(PiiType.class);
        int masked;
        int blocks;

        Ctx(SensitiveFields sensitive) {
            this.sensitive = sensitive;
        }

        boolean full() {
            return blocks >= MAX_BLOCKS;
        }
    }

    /**
     * Renders an answer.
     *
     * @param template  backend-authored layout, or {@code null} for the automatic layout
     * @param answer    the model's answer (raw; this method does the redaction)
     * @param sensitive keys that are always masked
     * @return the display-safe response
     */
    public StructuredResponse render(@Nullable DisplayTemplate template, String answer, SensitiveFields sensitive) {
        Ctx ctx = new Ctx(sensitive);
        AnswerContent content = AnswerContent.parse(answer);
        List<Object> blocks = template != null
                ? renderNodes(template.blocks(), content, ctx)
                : autoLayout(content, ctx);
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("version", DisplayTemplate.VERSION);
        tree.put("blocks", blocks);
        Map<String, Object> redactions = new LinkedHashMap<>();
        ctx.counts.forEach((type, n) -> redactions.put(type.name(), n));
        tree.put("redactions", redactions);
        tree.put("masked", ctx.masked);
        return new StructuredResponse(tree, ctx.counts, ctx.masked);
    }

    /**
     * A redacted JSON answer.
     *
     * @param text   the JSON text to return (unchanged when nothing was found)
     * @param counts personal-data values removed, per type
     * @param masked values masked because of their key
     */
    public record RedactedJson(String text, Map<PiiType, Integer> counts, int masked) {
        /** Copies the counts. */
        public RedactedJson {
            counts = Map.copyOf(counts);
        }
    }

    /**
     * Redacts a JSON answer (a {@code JSON_SCHEMA} agent's document) inside its structure, so the result stays valid
     * JSON: string values are redacted in place, numbers that are personal data become placeholder strings and values
     * under sensitive keys are masked. When nothing is found the original text is returned unchanged; otherwise the
     * redacted document is written canonically (ADR-0020). Text that is not JSON is redacted as text.
     *
     * @param json      the answer
     * @param sensitive keys that are always masked
     * @return the redacted answer
     */
    public RedactedJson redactJson(String json, SensitiveFields sensitive) {
        Object data = AnswerContent.parseJson(json);
        if (data == null) {
            PiiRedactor.Redaction r = redactor.redact(json);
            return new RedactedJson(r.text(), r.counts(), 0);
        }
        Ctx ctx = new Ctx(sensitive);
        Object redacted = redactTree(data, null, ctx);
        if (ctx.counts.isEmpty() && ctx.masked == 0) {
            return new RedactedJson(json, Map.of(), 0);
        }
        return new RedactedJson(CanonicalJson.write(redacted), ctx.counts, ctx.masked);
    }

    private @Nullable Object redactTree(@Nullable Object value, @Nullable String key, Ctx ctx) {
        if (value instanceof Map<?, ?> map) {
            Map<String, @Nullable Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), redactTree(v, String.valueOf(k), ctx)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<@Nullable Object> out = new ArrayList<>(list.size());
            list.forEach(v -> out.add(redactTree(v, key, ctx)));
            return out;
        }
        if (value == null) {
            return null;
        }
        if (key != null && ctx.sensitive.isSensitive(key)) {
            ctx.masked++;
            return MASK;
        }
        if (value instanceof String s) {
            return redactText(s, ctx);
        }
        if (value instanceof Number n) {
            String s = plain(n);
            String r = redactText(s, ctx);
            return r.equals(s) ? value : r;
        }
        return value;
    }

    // ─── Template layout ───────────────────────────────────────────────────────────────────────────

    private List<Object> renderNodes(List<DisplayNode> nodes, AnswerContent content, Ctx ctx) {
        List<Object> out = new ArrayList<>();
        for (DisplayNode node : nodes) {
            if (ctx.full()) {
                break;
            }
            Map<String, Object> block = switch (node) {
                case DisplayNode.Text t -> textBlock(t.title(), content.prose(), ctx);
                case DisplayNode.Fields f -> fieldsBlock(f, content.data(), ctx);
                case DisplayNode.Table t -> tableBlock(t, content.data(), ctx);
                case DisplayNode.Section s -> {
                    List<Object> children = renderNodes(s.children(), content, ctx);
                    if (children.isEmpty()) {
                        yield null;
                    }
                    Map<String, Object> b = block("section", s.title());
                    b.put("blocks", children);
                    yield b;
                }
            };
            if (block != null) {
                ctx.blocks++;
                out.add(block);
            }
        }
        return out;
    }

    private @Nullable Map<String, Object> textBlock(@Nullable String title, String prose, Ctx ctx) {
        if (prose.isBlank()) {
            return null;
        }
        Map<String, Object> b = block("text", title);
        b.put("text", redactText(prose, ctx));
        return b;
    }

    private @Nullable Map<String, Object> fieldsBlock(DisplayNode.Fields f, @Nullable Object data, Ctx ctx) {
        Object source = resolve(data, f.source());
        if (!(source instanceof Map<?, ?>)) {
            return null;
        }
        List<Object> items = new ArrayList<>();
        for (FieldSpec spec : f.fields()) {
            Object raw = resolve(source, spec.path());
            items.add(item(spec.path(), spec.label(), spec.format(),
                    cell(raw, lastSegment(spec.path()), spec.mask(), ctx)));
        }
        Map<String, Object> b = block("fields", f.title());
        b.put("items", items);
        return b;
    }

    private @Nullable Map<String, Object> tableBlock(DisplayNode.Table t, @Nullable Object data, Ctx ctx) {
        Object source = resolve(data, t.source());
        List<?> rows;
        if (source instanceof List<?> list) {
            rows = list;
        } else if (source instanceof Map<?, ?> single) {
            rows = List.of(single);
        } else {
            return null;
        }
        List<Object> columns = new ArrayList<>();
        for (FieldSpec c : t.columns()) {
            columns.add(column(c.path(), c.label(), c.format()));
        }
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < Math.min(rows.size(), t.maxRows()); i++) {
            Object row = rows.get(i);
            List<@Nullable Object> cells = new ArrayList<>();
            for (FieldSpec c : t.columns()) {
                cells.add(cell(resolve(row, c.path()), lastSegment(c.path()), c.mask(), ctx));
            }
            out.add(cells);
        }
        return table(t.title(), columns, out, rows.size(), rows.size() > t.maxRows());
    }

    // ─── Automatic layout ──────────────────────────────────────────────────────────────────────────

    private List<Object> autoLayout(AnswerContent content, Ctx ctx) {
        List<Object> blocks = new ArrayList<>();
        Map<String, Object> text = textBlock(null, content.prose(), ctx);
        if (text != null) {
            ctx.blocks++;
            blocks.add(text);
        }
        Object data = content.data();
        if (data instanceof Map<?, ?> map) {
            autoObject(map, null, 0, blocks, ctx);
        } else if (data instanceof List<?> list) {
            autoList(list, null, blocks, ctx);
        }
        return blocks;
    }

    private void autoObject(Map<?, ?> map, @Nullable String title, int depth, List<Object> blocks, Ctx ctx) {
        List<Object> items = new ArrayList<>();
        List<Runnable> nested = new ArrayList<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = String.valueOf(e.getKey());
            Object v = e.getValue();
            String label = redactText(DisplayTemplateParser.humanize(key), ctx);
            if (v instanceof Map<?, ?> child && depth + 1 < AUTO_MAX_DEPTH && !ctx.sensitive.isSensitive(key)) {
                nested.add(() -> autoObject(child, label, depth + 1, blocks, ctx));
            } else if (v instanceof List<?> list && !list.isEmpty() && list.stream().allMatch(x -> x instanceof Map)
                    && depth + 1 < AUTO_MAX_DEPTH && !ctx.sensitive.isSensitive(key)) {
                nested.add(() -> autoList(list, label, blocks, ctx));
            } else {
                items.add(item(redactText(key, ctx), label, inferFormat(v), cell(v, key, DisplayMask.NONE, ctx)));
            }
        }
        if (!items.isEmpty() && !ctx.full()) {
            Map<String, Object> b = block("fields", title);
            b.put("items", items);
            ctx.blocks++;
            blocks.add(b);
        }
        for (Runnable r : nested) {
            if (!ctx.full()) {
                r.run();
            }
        }
    }

    private void autoList(List<?> list, @Nullable String title, List<Object> blocks, Ctx ctx) {
        if (ctx.full() || list.isEmpty()) {
            return;
        }
        boolean objects = list.stream().allMatch(x -> x instanceof Map);
        List<Object> columns = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        if (objects) {
            Set<String> seen = new LinkedHashSet<>();
            for (Object row : list.subList(0, Math.min(list.size(), AUTO_MAX_ROWS))) {
                ((Map<?, ?>) row).forEach((k, v) -> {
                    if (!(v instanceof Map<?, ?>)) {
                        seen.add(String.valueOf(k));
                    }
                });
            }
            for (String k : seen) {
                if (keys.size() == AUTO_MAX_COLUMNS) {
                    break;
                }
                keys.add(k);
                Object sample = firstNonNull(list, k);
                columns.add(column(redactText(k, ctx), redactText(DisplayTemplateParser.humanize(k), ctx),
                        inferFormat(sample)));
            }
        } else {
            keys.add("$");
            columns.add(column("$", "Value", inferFormat(firstNonNull(list, "$"))));
        }
        List<Object> rows = new ArrayList<>();
        for (Object row : list.subList(0, Math.min(list.size(), AUTO_MAX_ROWS))) {
            List<@Nullable Object> cells = new ArrayList<>();
            for (String k : keys) {
                cells.add(cell(resolve(row, k), k.equals("$") ? "value" : k, DisplayMask.NONE, ctx));
            }
            rows.add(cells);
        }
        ctx.blocks++;
        blocks.add(table(title, columns, rows, list.size(), list.size() > AUTO_MAX_ROWS));
    }

    // ─── Values ────────────────────────────────────────────────────────────────────────────────────

    private @Nullable Object cell(@Nullable Object v, String key, DisplayMask mask, Ctx ctx) {
        if (v == null) {
            return null;
        }
        if (ctx.sensitive.isSensitive(key)) {
            ctx.masked++;
            return MASK;
        }
        if (v instanceof Map<?, ?>) {
            return "{…}";
        }
        if (v instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object element : list.subList(0, Math.min(list.size(), MAX_LIST_ELEMENTS))) {
                Object c = cell(element, key, mask, ctx);
                parts.add(c == null ? "" : String.valueOf(c));
            }
            return String.join(", ", parts) + (list.size() > MAX_LIST_ELEMENTS ? ", …" : "");
        }
        if (mask == DisplayMask.FULL) {
            ctx.masked++;
            return MASK;
        }
        if (v instanceof Boolean) {
            return v;
        }
        String s = v instanceof Number n ? plain(n) : String.valueOf(v);
        if (mask == DisplayMask.PARTIAL) {
            ctx.masked++;
            s = s.length() <= 8 ? MASK : "••••" + s.substring(s.length() - 4);
            return redactText(s, ctx);
        }
        String r = redactText(s, ctx);
        return v instanceof Number && r.equals(s) ? v : r;
    }

    private String redactText(String s, Ctx ctx) {
        PiiRedactor.Redaction r = redactor.redact(s);
        r.counts().forEach((type, n) -> ctx.counts.merge(type, n, Integer::sum));
        return r.text();
    }

    private static String plain(Number n) {
        return n instanceof BigDecimal bd ? bd.toPlainString() : String.valueOf(n);
    }

    private static DisplayFormat inferFormat(@Nullable Object v) {
        return switch (v) {
            case Number ignored -> DisplayFormat.NUMBER;
            case Boolean ignored -> DisplayFormat.BOOLEAN;
            case String s when DATETIME.matcher(s).matches() -> DisplayFormat.DATETIME;
            case String s when DATE.matcher(s).matches() -> DisplayFormat.DATE;
            case null, default -> DisplayFormat.TEXT;
        };
    }

    private static @Nullable Object firstNonNull(List<?> rows, String key) {
        for (Object row : rows) {
            Object v = resolve(row, key);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /** Follows a dot path through maps and lists; {@code null} or {@code $} is the value itself. */
    static @Nullable Object resolve(@Nullable Object root, @Nullable String path) {
        if (path == null || path.equals("$")) {
            return root;
        }
        Object current = root;
        for (String seg : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(seg);
            } else if (current instanceof List<?> list && seg.chars().allMatch(Character::isDigit)
                    && seg.length() < 9 && Integer.parseInt(seg) < list.size()) {
                current = list.get(Integer.parseInt(seg));
            } else {
                return null;
            }
        }
        return current;
    }

    private static String lastSegment(String path) {
        return path.substring(path.lastIndexOf('.') + 1);
    }

    // ─── Blocks ────────────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> block(String type, @Nullable String title) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("type", type);
        if (title != null) {
            b.put("title", title);
        }
        return b;
    }

    private static Map<String, @Nullable Object> item(String key, String label, DisplayFormat format,
                                                      @Nullable Object value) {
        Map<String, @Nullable Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("format", format.name().toLowerCase(java.util.Locale.ROOT));
        m.put("value", value);
        return m;
    }

    private static Map<String, Object> column(String key, String label, DisplayFormat format) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("format", format.name().toLowerCase(java.util.Locale.ROOT));
        return m;
    }

    private static Map<String, Object> table(@Nullable String title, List<Object> columns, List<Object> rows,
                                             int totalRows, boolean truncated) {
        Map<String, Object> b = block("table", title);
        b.put("columns", columns);
        b.put("rows", rows);
        b.put("totalRows", totalRows);
        b.put("truncated", truncated);
        return b;
    }
}
