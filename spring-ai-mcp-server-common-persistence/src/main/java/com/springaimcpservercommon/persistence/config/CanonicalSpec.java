package com.springaimcpservercommon.persistence.config;

import com.springaimcpservercommon.core.hash.Sha256;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A JSON object in canonical form together with its hash — the {@code spec}/{@code spec_hash} pair of a revision.
 *
 * <p><b>Canonicalisation</b> (modelled on RFC 8785 / JCS, but with decimal instead of IEEE-754 number rendering,
 * so that values survive PostgreSQL {@code jsonb}/{@code numeric} round trips unchanged):
 * <ol>
 *   <li>The input is parsed strictly (no trailing tokens); floating point numbers are read as exact decimals.
 *       The root must be an object. For duplicate keys the last one wins (same as {@code jsonb}).</li>
 *   <li>Object members are sorted by key, comparing UTF-16 code units ({@link String#compareTo}); array order is
 *       kept.</li>
 *   <li>No insignificant whitespace; {@code ,} and {@code :} without spaces.</li>
 *   <li>Strings: {@code "} and {@code \} are escaped, control characters U+0000–U+001F use {@code \b \f \n \r \t} or
 *       {@code \}{@code u00xx} (lowercase hex); every other character, including non-ASCII, is written as is.</li>
 *   <li>Numbers: rendered as plain decimals without exponent and without trailing fractional zeros, so
 *       {@code 1}, {@code 1.0} and {@code 1e0} all become {@code 1}; {@code -0} becomes {@code 0}. Numbers whose plain
 *       form would exceed {@value #MAX_NUMBER_DIGITS} digits are rejected.</li>
 *   <li>{@code true}, {@code false}, {@code null} as literals.</li>
 * </ol>
 * The hash is {@link Sha256#of(String) sha256} over the UTF-8 bytes of the canonical text ({@code sha256:<hex>}).
 * Because {@code jsonb} re-orders keys and re-formats whitespace, the stored spec must always be canonicalised again
 * before its hash is recomputed — which this class makes idempotent: {@code of(of(x).json()).equals(of(x))}.
 *
 * @param json canonical JSON text of an object
 * @param hash {@code sha256:<hex>} of {@code json}
 */
public record CanonicalSpec(String json, String hash) {

    /** Largest number of digits a canonical number may have (guards against {@code 1e999999999}). */
    public static final int MAX_NUMBER_DIGITS = 200;

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /**
     * Validates that {@code hash} matches {@code json}. Use {@link #of(String)} to canonicalise arbitrary input.
     *
     * @param json canonical JSON
     * @param hash its hash
     */
    public CanonicalSpec {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(hash, "hash");
        if (!hash.equals(Sha256.of(json))) {
            throw new IllegalArgumentException("hash does not match the canonical JSON");
        }
    }

    /**
     * Canonicalises a JSON object and hashes it.
     *
     * @param json JSON text whose root is an object
     * @return canonical form and hash
     * @throws IllegalArgumentException if the text is not a JSON object (the message never contains the content)
     */
    public static CanonicalSpec of(String json) {
        String canonical = canonicalObject(json);
        return new CanonicalSpec(canonical, Sha256.of(canonical));
    }

    /**
     * Canonicalises a JSON object without hashing it (used for grant conditions).
     *
     * @param json JSON text whose root is an object
     * @return canonical JSON text
     * @throws IllegalArgumentException if the text is not a JSON object
     */
    public static String canonicalObject(String json) {
        Objects.requireNonNull(json, "json");
        Object root;
        try {
            root = MAPPER.readValue(json, Object.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("not valid JSON: " + e.getOriginalMessage());
        }
        if (!(root instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("JSON root must be an object");
        }
        StringBuilder out = new StringBuilder(json.length());
        write(root, out);
        return out.toString();
    }

    private static void write(@Nullable Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case Map<?, ?> map -> {
                TreeMap<String, @Nullable Object> sorted = new TreeMap<>();
                map.forEach((k, v) -> sorted.put(String.valueOf(k), v));
                out.append('{');
                boolean first = true;
                for (Map.Entry<String, @Nullable Object> e : sorted.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    writeString(e.getKey(), out);
                    out.append(':');
                    write(e.getValue(), out);
                }
                out.append('}');
            }
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    write(list.get(i), out);
                }
                out.append(']');
            }
            case String s -> writeString(s, out);
            case Boolean b -> out.append(b.booleanValue());
            case BigDecimal d -> out.append(plain(d));
            case BigInteger i -> out.append(plain(new BigDecimal(i)));
            case Integer i -> out.append(i.intValue());
            case Long l -> out.append(l.longValue());
            case Short s -> out.append(s.shortValue());
            case Number n -> out.append(plain(new BigDecimal(n.toString())));
            default -> throw new IllegalArgumentException("unsupported JSON value type " + value.getClass().getName());
        }
    }

    private static String plain(BigDecimal value) {
        if (value.signum() == 0) {
            return "0";
        }
        BigDecimal stripped = value.stripTrailingZeros();
        int integerDigits = Math.max(stripped.precision() - stripped.scale(), 1);
        int fractionDigits = Math.max(stripped.scale(), 0);
        if (integerDigits + fractionDigits > MAX_NUMBER_DIGITS) {
            throw new IllegalArgumentException("JSON number exceeds " + MAX_NUMBER_DIGITS + " digits");
        }
        return stripped.toPlainString();
    }

    private static void writeString(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
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
}
