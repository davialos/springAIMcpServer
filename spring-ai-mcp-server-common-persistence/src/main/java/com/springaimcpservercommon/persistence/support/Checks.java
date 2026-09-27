package com.springaimcpservercommon.persistence.support;

import com.springaimcpservercommon.core.hash.Sha256;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Argument checks used by entity factories to mirror the store's CHECK constraints in Java, so invalid rows are
 * rejected with a precise message before they reach the database.
 */
public final class Checks {

    private Checks() {
    }

    /**
     * Requires a non-null value.
     *
     * @param value value
     * @param name  argument name for the message
     * @param <T>   type
     * @return the value
     */
    public static <T> T required(@Nullable T value, String name) {
        return Objects.requireNonNull(value, name + " is required");
    }

    /**
     * Requires non-blank text of at most {@code maxLength} characters.
     *
     * @param value     text
     * @param name      argument name
     * @param maxLength maximum length
     * @return the text
     */
    public static String text(@Nullable String value, String name, int maxLength) {
        required(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must be at most " + maxLength + " characters");
        }
        return value;
    }

    /**
     * Like {@link #text(String, String, int)} but allows {@code null}.
     *
     * @param value     text or {@code null}
     * @param name      argument name
     * @param maxLength maximum length
     * @return the text or {@code null}
     */
    public static @Nullable String optionalText(@Nullable String value, String name, int maxLength) {
        return value == null ? null : text(value, name, maxLength);
    }

    /**
     * Requires text matching a pattern (the pattern of the corresponding CHECK constraint).
     *
     * @param value   text
     * @param pattern pattern that must match the whole text
     * @param name    argument name
     * @return the text
     */
    public static String matches(@Nullable String value, Pattern pattern, String name) {
        required(value, name);
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must match " + pattern.pattern() + ": " + value);
        }
        return value;
    }

    /**
     * Like {@link #matches(String, Pattern, String)} but allows {@code null}.
     *
     * @param value   text or {@code null}
     * @param pattern pattern
     * @param name    argument name
     * @return the text or {@code null}
     */
    public static @Nullable String optionalMatches(@Nullable String value, Pattern pattern, String name) {
        return value == null ? null : matches(value, pattern, name);
    }

    /**
     * Requires a well-formed {@code sha256:<64 hex>} digest.
     *
     * @param value digest
     * @param name  argument name
     * @return the digest
     */
    public static String sha256(@Nullable String value, String name) {
        required(value, name);
        if (!Sha256.isValid(value)) {
            throw new IllegalArgumentException(name + " must be sha256:<64 lowercase hex>");
        }
        return value;
    }

    /**
     * Requires {@code value >= 0}.
     *
     * @param value number
     * @param name  argument name
     * @return the number
     */
    public static long nonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative: " + value);
        }
        return value;
    }

    /**
     * Requires {@code end >= start}.
     *
     * @param start start instant
     * @param end   end instant
     * @param what  description for the message
     */
    public static void notBefore(Instant start, Instant end, String what) {
        if (end.isBefore(start)) {
            throw new IllegalArgumentException(what + ": end must not be before start");
        }
    }

    /**
     * Validates optional JSON text and returns it canonicalised (compact) or {@code null}.
     *
     * @param json JSON text or {@code null}
     * @param name argument name
     * @return canonical JSON or {@code null}
     */
    public static @Nullable String optionalJson(@Nullable String json, String name) {
        if (json == null) {
            return null;
        }
        try {
            return CanonicalJson.canonicalize(json);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + " is not valid JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Validates optional JSON object text and returns it canonicalised or {@code null}.
     *
     * @param json JSON object text or {@code null}
     * @param name argument name
     * @return canonical JSON object or {@code null}
     */
    public static @Nullable String optionalJsonObject(@Nullable String json, String name) {
        if (json == null) {
            return null;
        }
        try {
            return CanonicalJson.canonicalizeObject(json);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + " is not a valid JSON object: " + e.getMessage(), e);
        }
    }
}
