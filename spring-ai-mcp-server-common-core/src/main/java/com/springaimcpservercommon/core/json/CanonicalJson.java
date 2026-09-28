package com.springaimcpservercommon.core.json;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Minimal, dependency-free writer for <em>canonical</em> JSON: object keys sorted by {@link String#compareTo},
 * no insignificant whitespace, strings escaped per RFC 8259. Used for fingerprints (scan and policy hashes) and
 * for {@code JsonSchema} documents so that equal content always renders to identical bytes, independent of the
 * host's {@code JsonMapper} configuration (which the library must never touch, ADR-0019).
 *
 * <p><b>This is the single canonical-JSON writer for the whole codebase (ADR-0020).</b> Every module that needs
 * to render a value tree for hashing or export — including {@code persistence.support.CanonicalJson}'s parser
 * output and {@code persistence.config.CanonicalSpec}'s Jackson-parsed specs — must render through {@link #write}
 * rather than re-implementing escaping or number formatting, so the same logical content always produces the
 * same bytes no matter which module computed it.
 *
 * <p>Supported values: {@code null}, {@link Boolean}, {@link String}, {@link Character}, {@link Enum} (by name),
 * {@link UUID} (its string form), integral numbers ({@link Byte}, {@link Short}, {@link Integer}, {@link Long},
 * {@link BigInteger}), {@link BigDecimal} (plain notation, trailing zeros stripped), finite
 * {@link Double}/{@link Float}, {@link Map} with {@link String} keys and {@link Collection}s.
 */
public final class CanonicalJson {

    /**
     * Largest nesting depth {@link #write} and {@link #immutableCopy} will descend, guarding against a
     * {@link StackOverflowError} from a deeply nested (or maliciously crafted) value tree. Matches the depth limit
     * independently chosen by both callers this class now replaces (ADR-0020).
     */
    public static final int MAX_DEPTH = 128;

    private CanonicalJson() {
    }

    /**
     * Renders a value tree as canonical JSON.
     *
     * @param value the value tree
     * @return canonical JSON text
     * @throws IllegalArgumentException for unsupported values, non-string map keys, non-finite numbers or nesting
     *                                   deeper than {@value #MAX_DEPTH}
     */
    public static String write(@Nullable Object value) {
        StringBuilder out = new StringBuilder(256);
        append(out, value, 0);
        return out.toString();
    }

    /**
     * Returns a deeply immutable copy of a value tree: maps become unmodifiable {@link TreeMap}-ordered maps,
     * collections become unmodifiable lists. Scalars are returned as is.
     *
     * @param value the value tree
     * @return an immutable copy
     * @throws IllegalArgumentException for unsupported values or nesting deeper than {@value #MAX_DEPTH}
     */
    public static @Nullable Object immutableCopy(@Nullable Object value) {
        return immutableCopy(value, 0);
    }

    private static @Nullable Object immutableCopy(@Nullable Object value, int depth) {
        checkDepth(depth);
        return switch (value) {
            case null -> null;
            case Map<?, ?> map -> {
                Map<String, @Nullable Object> copy = new TreeMap<>();
                map.forEach((k, v) -> copy.put(key(k), immutableCopy(v, depth + 1)));
                yield Collections.unmodifiableMap(new LinkedHashMap<>(copy));
            }
            case Collection<?> collection -> {
                List<@Nullable Object> copy = new ArrayList<>(collection.size());
                collection.forEach(v -> copy.add(immutableCopy(v, depth + 1)));
                yield Collections.unmodifiableList(copy);
            }
            default -> {
                checkScalar(value);
                yield value;
            }
        };
    }

    private static void append(StringBuilder out, @Nullable Object value, int depth) {
        checkDepth(depth);
        switch (value) {
            case null -> out.append("null");
            case Boolean b -> out.append(b);
            case String s -> appendString(out, s);
            case Character c -> appendString(out, c.toString());
            case Enum<?> e -> appendString(out, e.name());
            case UUID u -> appendString(out, u.toString());
            case Byte n -> out.append(n.longValue());
            case Short n -> out.append(n.longValue());
            case Integer n -> out.append(n.longValue());
            case Long n -> out.append(n.longValue());
            case BigInteger n -> out.append(n);
            case BigDecimal n -> out.append(n.signum() == 0 ? "0" : n.stripTrailingZeros().toPlainString());
            case Double d -> appendFloating(out, d);
            case Float f -> appendFloating(out, f.doubleValue());
            case Map<?, ?> map -> {
                Map<String, @Nullable Object> sorted = new TreeMap<>();
                map.forEach((k, v) -> sorted.put(key(k), v));
                out.append('{');
                boolean first = true;
                for (Map.Entry<String, @Nullable Object> entry : sorted.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    appendString(out, entry.getKey());
                    out.append(':');
                    append(out, entry.getValue(), depth + 1);
                }
                out.append('}');
            }
            case Collection<?> collection -> {
                out.append('[');
                boolean first = true;
                for (Object element : collection) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    append(out, element, depth + 1);
                }
                out.append(']');
            }
            default -> throw new IllegalArgumentException("unsupported JSON value type: " + value.getClass().getName());
        }
    }

    private static void checkDepth(int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("JSON nesting deeper than " + MAX_DEPTH);
        }
    }

    private static void appendFloating(StringBuilder out, double d) {
        if (!Double.isFinite(d)) {
            throw new IllegalArgumentException("non-finite number is not valid JSON: " + d);
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            out.append((long) d);
        } else {
            out.append(BigDecimal.valueOf(d).stripTrailingZeros().toPlainString());
        }
    }

    private static String key(@Nullable Object key) {
        if (key instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException("JSON object keys must be strings, got: " + key);
    }

    private static void checkScalar(Object value) {
        if (!(value instanceof Boolean || value instanceof String || value instanceof Character
                || value instanceof Enum<?> || value instanceof UUID || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long || value instanceof BigInteger
                || value instanceof BigDecimal || value instanceof Double || value instanceof Float)) {
            throw new IllegalArgumentException("unsupported JSON value type: " + value.getClass().getName());
        }
    }

    private static void appendString(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
