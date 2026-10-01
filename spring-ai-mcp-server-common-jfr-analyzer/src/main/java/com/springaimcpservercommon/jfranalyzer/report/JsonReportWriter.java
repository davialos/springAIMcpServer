package com.springaimcpservercommon.jfranalyzer.report;

import com.springaimcpservercommon.core.json.CanonicalJson;
import com.springaimcpservercommon.jfranalyzer.model.AnalysisReport;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a report as JSON. The report records are converted to a plain value tree (maps, lists, numbers,
 * strings) and rendered by {@link CanonicalJson}, the codebase's single JSON writer (ADR-0020): keys sorted,
 * deterministic bytes, so two reports of the same recording diff cleanly. {@link #indent} only re-flows that text.
 */
public final class JsonReportWriter {

    private JsonReportWriter() {
    }

    /**
     * @param report the report
     * @param pretty indent with two spaces
     * @return JSON text
     */
    public static String write(AnalysisReport report, boolean pretty) {
        String json = CanonicalJson.write(tree(report));
        return pretty ? indent(json) : json;
    }

    /**
     * Converts records, lists, maps and enums to a value tree {@link CanonicalJson} accepts. Doubles are rounded to
     * three decimals and non-finite values become {@code null}.
     *
     * @param value a model value
     * @return the value tree
     */
    static @Nullable Object tree(@Nullable Object value) {
        return switch (value) {
            case null -> null;
            case Record r -> {
                Map<String, Object> map = new LinkedHashMap<>();
                for (RecordComponent c : r.getClass().getRecordComponents()) {
                    map.put(c.getName(), tree(component(r, c)));
                }
                yield map;
            }
            case Map<?, ?> m -> {
                Map<String, Object> map = new LinkedHashMap<>();
                m.forEach((k, v) -> map.put(String.valueOf(k), tree(v)));
                yield map;
            }
            case Collection<?> c -> {
                List<Object> list = new ArrayList<>(c.size());
                c.forEach(v -> list.add(tree(v)));
                yield list;
            }
            case Double d -> Double.isFinite(d) ? Math.round(d * 1000) / 1000.0 : null;
            case Float f -> Float.isFinite(f) ? Math.round(f * 1000) / 1000.0 : null;
            case Enum<?> e -> e.name();
            case String s -> s;
            case Number n -> n;
            case Boolean b -> b;
            default -> value.toString();
        };
    }

    private static @Nullable Object component(Record r, RecordComponent c) {
        try {
            return c.getAccessor().invoke(r);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Cannot read " + r.getClass().getSimpleName() + "." + c.getName(), e);
        }
    }

    /**
     * Inserts newlines and two-space indentation into compact JSON text without changing any token.
     *
     * @param json compact JSON
     * @return indented JSON
     */
    static String indent(String json) {
        StringBuilder out = new StringBuilder(json.length() + json.length() / 4);
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                out.append(c);
                if (c == '\\') {
                    out.append(json.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    inString = true;
                    out.append(c);
                }
                case '{', '[' -> {
                    char close = c == '{' ? '}' : ']';
                    if (i + 1 < json.length() && json.charAt(i + 1) == close) {
                        out.append(c).append(close);
                        i++;
                    } else {
                        depth++;
                        out.append(c).append('\n').append("  ".repeat(depth));
                    }
                }
                case '}', ']' -> {
                    depth--;
                    out.append('\n').append("  ".repeat(depth)).append(c);
                }
                case ',' -> out.append(",\n").append("  ".repeat(depth));
                case ':' -> out.append(": ");
                default -> out.append(c);
            }
        }
        return out.append('\n').toString();
    }
}
