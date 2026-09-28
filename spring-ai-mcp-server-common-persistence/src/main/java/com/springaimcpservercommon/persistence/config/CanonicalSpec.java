package com.springaimcpservercommon.persistence.config;

import com.springaimcpservercommon.core.hash.Sha256;
import com.springaimcpservercommon.core.json.CanonicalJson;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * A JSON object in canonical form together with its hash — the {@code spec}/{@code spec_hash} pair of a revision.
 *
 * <p><b>Parsing</b> is strict Jackson 3 (no trailing tokens; floating-point numbers read as exact
 * {@link BigDecimal}s, so values survive PostgreSQL {@code jsonb}/{@code numeric} round trips unchanged). The root
 * must be an object. For duplicate keys the last one wins (same as {@code jsonb}). Numbers whose plain-decimal form
 * would exceed {@value #MAX_NUMBER_DIGITS} digits are rejected before rendering (guards against a crafted
 * {@code 1e999999999}-style admin-submitted value).
 *
 * <p><b>Rendering</b> delegates to {@link CanonicalJson#write} — the single canonical-JSON writer for the whole
 * codebase (ADR-0020) — so a spec's hash is computed the same way as every other hashed value in the system
 * (audit events, proposal payloads, catalog/policy fingerprints).
 *
 * <p>The hash is {@link Sha256#of(String) sha256} over the UTF-8 bytes of the canonical text ({@code sha256:<hex>}).
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
        checkNumberSizes(root);
        return CanonicalJson.write(root);
    }

    /**
     * Walks the parsed tree rejecting any number whose plain-decimal form would exceed
     * {@value #MAX_NUMBER_DIGITS} digits, before the value ever reaches the writer. Runs only over the shapes
     * Jackson's untyped binding produces ({@code Map}, {@code Collection}, {@code Number}, {@code String},
     * {@code Boolean}, {@code null}).
     *
     * @param value a node of the parsed tree
     */
    private static void checkNumberSizes(@Nullable Object value) {
        switch (value) {
            case null -> { }
            case Map<?, ?> map -> map.values().forEach(CanonicalSpec::checkNumberSizes);
            case Collection<?> collection -> collection.forEach(CanonicalSpec::checkNumberSizes);
            case BigDecimal d -> plain(d);
            case BigInteger i -> plain(new BigDecimal(i));
            case Number n when !(n instanceof Integer) && !(n instanceof Long) && !(n instanceof Short) -> plain(new BigDecimal(n.toString()));
            default -> { }
        }
    }

    /**
     * The plain-decimal form of {@code value}, used only to enforce {@value #MAX_NUMBER_DIGITS} before rendering —
     * {@link CanonicalJson#write} performs the equivalent {@code stripTrailingZeros().toPlainString()} rendering
     * itself, so this method's return value is a size check, not the text that ends up in the output.
     *
     * @param value the number to check
     * @return its plain-decimal form (for the caller's own use, if needed)
     * @throws IllegalArgumentException if the plain form would exceed {@value #MAX_NUMBER_DIGITS} digits
     */
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
}
