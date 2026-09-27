package com.springaimcpservercommon.persistence.support;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Minimal, dependency-free JSON parser and canonical writer used for every JSON value the store hashes or builds
 * (audit {@code details}, proposal event details). It deliberately does not use Jackson: the output format must never
 * change with a library upgrade, because audit hashes computed today must verify in ten years.
 *
 * <h2>Canonical form (version 1)</h2>
 * <ul>
 *   <li>No insignificant whitespace.</li>
 *   <li>Object members sorted by key, comparing UTF-16 code units ({@link String#compareTo}); duplicate keys keep the
 *       last value (the same rule as PostgreSQL {@code jsonb}).</li>
 *   <li>Strings: {@code "} and {@code \} escaped as {@code \"} and {@code \\}; U+0008, U+0009, U+000A, U+000C, U+000D as
 *       {@code \b \t \n \f \r}; other characters below U+0020 as a backslash, {@code u00} and two lowercase hex digits; every other character
 *       literally (the hash input is UTF-8). Lone surrogates are rejected (as {@code jsonb} does).</li>
 *   <li>Numbers: parsed exactly as {@link BigDecimal} and written as {@code stripTrailingZeros().toPlainString()}, so
 *       {@code 1.50}, {@code 1.5} and {@code 15e-1} are all {@code 1.5}, and {@code -0} is {@code 0} — equal numbers in
 *       {@code jsonb} are equal here, whatever text PostgreSQL returns for them. Exponents beyond ±1000 are rejected.</li>
 *   <li>{@code true}, {@code false}, {@code null} as literals.</li>
 * </ul>
 * Because JSON read back from a {@code jsonb} column is re-canonicalised before hashing, the whitespace and key order
 * PostgreSQL uses for output do not matter.
 */
public final class CanonicalJson {

    private static final int MAX_DEPTH = 128;
    private static final int MAX_EXPONENT = 1000;

    private CanonicalJson() {
    }

    /**
     * Parses JSON text and writes it back in canonical form.
     *
     * @param json JSON text
     * @return canonical JSON
     * @throws IllegalArgumentException if the text is not valid JSON
     */
    public static String canonicalize(String json) {
        return write(parse(json));
    }

    /**
     * Like {@link #canonicalize(String)} but requires the top-level value to be an object.
     *
     * @param json JSON text
     * @return canonical JSON object
     * @throws IllegalArgumentException if the text is not a JSON object
     */
    public static String canonicalizeObject(String json) {
        Object value = parse(json);
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("JSON value must be an object");
        }
        return write(value);
    }

    /**
     * Parses JSON text into {@code Map<String, Object>} (insertion ordered), {@code List<Object>}, {@code String},
     * {@link BigDecimal}, {@code Boolean} or {@code null}.
     *
     * @param json JSON text
     * @return the parsed value
     * @throws IllegalArgumentException if the text is not valid JSON
     */
    public static @Nullable Object parse(String json) {
        Parser parser = new Parser(json);
        parser.skipWhitespace();
        Object value = parser.readValue(0);
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("unexpected trailing content");
        }
        return value;
    }

    /**
     * Writes a value in canonical form. Supported: {@code Map} with {@code String} keys, {@code Collection},
     * {@code String}, {@code Number} (integral types, {@code BigInteger}, {@code BigDecimal}), {@code Boolean},
     * {@code Enum} (its name), {@code UUID} (its string form) and {@code null}.
     *
     * @param value value to write
     * @return canonical JSON
     * @throws IllegalArgumentException for unsupported types or non-finite floating point numbers
     */
    public static String write(@Nullable Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value, 0);
        return out.toString();
    }

    /**
     * Renders a string as a canonical JSON string literal.
     *
     * @param text the text
     * @return quoted and escaped literal
     */
    public static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2);
        writeString(out, text);
        return out.toString();
    }

    private static void writeValue(StringBuilder out, @Nullable Object value, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("JSON nesting deeper than " + MAX_DEPTH);
        }
        switch (value) {
            case null -> out.append("null");
            case String s -> writeString(out, s);
            case Boolean b -> out.append(b ? "true" : "false");
            case BigDecimal d -> out.append(canonicalNumber(d));
            case BigInteger i -> out.append(i);
            case Long l -> out.append(l.longValue());
            case Integer i -> out.append(i.intValue());
            case Short s -> out.append(s.shortValue());
            case Byte b -> out.append(b.byteValue());
            case Double d -> out.append(canonicalNumber(finite(d)));
            case Float f -> out.append(canonicalNumber(finite(f.doubleValue())));
            case Enum<?> e -> writeString(out, e.name());
            case UUID u -> writeString(out, u.toString());
            case Map<?, ?> map -> writeObject(out, map, depth);
            case Collection<?> list -> {
                out.append('[');
                boolean first = true;
                for (Object element : list) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    writeValue(out, element, depth + 1);
                }
                out.append(']');
            }
            default -> throw new IllegalArgumentException("unsupported JSON value type " + value.getClass().getName());
        }
    }

    private static void writeObject(StringBuilder out, Map<?, ?> map, int depth) {
        TreeMap<String, @Nullable Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("JSON object keys must be strings");
            }
            sorted.put(key, entry.getValue());
        }
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, @Nullable Object> entry : sorted.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeString(out, entry.getKey());
            out.append(':');
            writeValue(out, entry.getValue(), depth + 1);
        }
        out.append('}');
    }

    private static BigDecimal finite(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException("JSON numbers must be finite");
        }
        return new BigDecimal(Double.toString(d));
    }

    private static String canonicalNumber(BigDecimal d) {
        if (d.signum() == 0) {
            return "0";
        }
        return d.stripTrailingZeros().toPlainString();
    }

    private static void writeString(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
                    throw new IllegalArgumentException("lone surrogate in JSON string");
                }
                out.append(c).append(s.charAt(++i));
                continue;
            }
            if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("lone surrogate in JSON string");
            }
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\f' -> out.append("\\f");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u00").append(Character.forDigit(c >> 4, 16)).append(Character.forDigit(c & 0xF, 16));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** Recursive-descent parser (RFC 8259, strict). */
    private static final class Parser {

        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException("invalid JSON at offset " + pos + ": " + message);
        }

        void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    return;
                }
            }
        }

        @Nullable Object readValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("nesting deeper than " + MAX_DEPTH);
            }
            if (atEnd()) {
                throw error("unexpected end of input");
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> readObject(depth);
                case '[' -> readArray(depth);
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield readNumber();
                    }
                    throw error("unexpected character '" + c + "'");
                }
            };
        }

        private @Nullable Object readLiteral(String literal, @Nullable Object value) {
            if (!text.startsWith(literal, pos)) {
                throw error("expected " + literal);
            }
            pos += literal.length();
            return value;
        }

        private Map<String, @Nullable Object> readObject(int depth) {
            pos++; // '{'
            Map<String, @Nullable Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (!atEnd() && text.charAt(pos) == '}') {
                pos++;
                return result;
            }
            while (true) {
                skipWhitespace();
                if (atEnd() || text.charAt(pos) != '"') {
                    throw error("expected object key");
                }
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                Object value = readValue(depth + 1);
                result.remove(key); // last duplicate wins, like jsonb
                result.put(key, value);
                skipWhitespace();
                if (atEnd()) {
                    throw error("unterminated object");
                }
                char c = text.charAt(pos++);
                if (c == '}') {
                    return result;
                }
                if (c != ',') {
                    throw error("expected ',' or '}'");
                }
            }
        }

        private List<@Nullable Object> readArray(int depth) {
            pos++; // '['
            List<@Nullable Object> result = new ArrayList<>();
            skipWhitespace();
            if (!atEnd() && text.charAt(pos) == ']') {
                pos++;
                return result;
            }
            while (true) {
                skipWhitespace();
                result.add(readValue(depth + 1));
                skipWhitespace();
                if (atEnd()) {
                    throw error("unterminated array");
                }
                char c = text.charAt(pos++);
                if (c == ']') {
                    return result;
                }
                if (c != ',') {
                    throw error("expected ',' or ']'");
                }
            }
        }

        private void expect(char expected) {
            if (atEnd() || text.charAt(pos) != expected) {
                throw error("expected '" + expected + "'");
            }
            pos++;
        }

        private String readString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw error("unterminated string");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    break;
                }
                if (c < 0x20) {
                    throw error("unescaped control character in string");
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw error("unterminated escape");
                }
                char e = text.charAt(pos++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> sb.append(readHex4());
                    default -> throw error("invalid escape '\\" + e + "'");
                }
            }
            String s = sb.toString();
            validateSurrogates(s);
            return s;
        }

        private char readHex4() {
            if (pos + 4 > text.length()) {
                throw error("truncated unicode escape");
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(text.charAt(pos++), 16);
                if (digit < 0) {
                    throw error("invalid unicode escape");
                }
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private void validateSurrogates(String s) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
                        throw error("lone surrogate in string");
                    }
                    i++;
                } else if (Character.isLowSurrogate(c)) {
                    throw error("lone surrogate in string");
                }
            }
        }

        private BigDecimal readNumber() {
            int start = pos;
            if (text.charAt(pos) == '-') {
                pos++;
            }
            if (atEnd()) {
                throw error("truncated number");
            }
            if (text.charAt(pos) == '0') {
                pos++;
            } else if (isDigit()) {
                while (isDigit()) {
                    pos++;
                }
            } else {
                throw error("invalid number");
            }
            if (!atEnd() && text.charAt(pos) == '.') {
                pos++;
                if (!isDigit()) {
                    throw error("digit expected after decimal point");
                }
                while (isDigit()) {
                    pos++;
                }
            }
            if (!atEnd() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                pos++;
                if (!atEnd() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                    pos++;
                }
                if (!isDigit()) {
                    throw error("digit expected in exponent");
                }
                int expStart = pos;
                while (isDigit()) {
                    pos++;
                }
                if (pos - expStart > 4 || Integer.parseInt(text, expStart, pos, 10) > MAX_EXPONENT) {
                    throw error("exponent out of range");
                }
            }
            return new BigDecimal(text.substring(start, pos));
        }

        private boolean isDigit() {
            return !atEnd() && text.charAt(pos) >= '0' && text.charAt(pos) <= '9';
        }
    }
}
