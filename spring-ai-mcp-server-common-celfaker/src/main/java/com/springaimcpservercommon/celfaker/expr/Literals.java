package com.springaimcpservercommon.celfaker.expr;

import com.springaimcpservercommon.ruleengine.model.DataType;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Renders plain Java values as CEL literals. */
public final class Literals {

    private Literals() {
    }

    /**
     * Renders a value of a library type as a CEL literal.
     *
     * @param type  the CEL type of the value
     * @param value the value (ISO-8601 text for TIMESTAMP and DURATION)
     * @return CEL source
     */
    public static String of(DataType type, Object value) {
        return switch (type) {
            case STRING -> string(String.valueOf(value));
            case INT -> Long.toString(((Number) value).longValue());
            case DOUBLE -> dbl(((Number) value).doubleValue());
            case BOOL -> String.valueOf(value);
            case TIMESTAMP -> "timestamp(" + string(String.valueOf(value)) + ")";
            case DURATION -> duration(Duration.parse(String.valueOf(value)));
            case LIST_STRING, LIST_INT, LIST_DOUBLE, MAP, ANY -> dynamic(value);
        };
    }

    /**
     * Renders a quoted, escaped CEL string.
     *
     * @param s text
     * @return literal
     */
    public static String string(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20 || ch == 0x7f) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /**
     * Renders a double with a decimal point and no exponent.
     *
     * @param d value
     * @return literal
     */
    public static String dbl(double d) {
        String plain = BigDecimal.valueOf(d).toPlainString();
        return plain.contains(".") ? plain : plain + ".0";
    }

    /**
     * Renders a duration as {@code duration("3600s")}.
     *
     * @param d duration
     * @return literal
     */
    public static String duration(Duration d) {
        return "duration(\"" + d.toSeconds() + "s\")";
    }

    private static String dynamic(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof String s) {
            return string(s);
        }
        if (v instanceof Boolean b) {
            return b.toString();
        }
        if (v instanceof Double || v instanceof Float) {
            return dbl(((Number) v).doubleValue());
        }
        if (v instanceof Number n) {
            return Long.toString(n.longValue());
        }
        if (v instanceof List<?> l) {
            return l.stream().map(Literals::dynamic).collect(Collectors.joining(", ", "[", "]"));
        }
        if (v instanceof Map<?, ?> m) {
            return m.entrySet().stream().map(e -> string(String.valueOf(e.getKey())) + ": " + dynamic(e.getValue()))
                    .collect(Collectors.joining(", ", "{", "}"));
        }
        return string(String.valueOf(v));
    }
}
