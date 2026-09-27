package com.springaimcpservercommon.core.lint;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Checks prompt-bound text (descriptions, intents, meanings, keywords) against the limits of LLD-02 §4:
 * not blank, bounded length (prompt budget) and free of secret-looking values.
 */
public final class TextLint {

    /** Maximum length of a {@code description} / {@code intent} / {@code descriptionOverride}. */
    public static final int MAX_DESCRIPTION_LENGTH = 1024;

    /** Maximum length of an attribute {@code meaning}. */
    public static final int MAX_MEANING_LENGTH = 256;

    /** Maximum length of a single keyword. */
    public static final int MAX_KEYWORD_LENGTH = 64;

    /** Maximum number of keywords per element. */
    public static final int MAX_KEYWORDS = 32;

    /** Kind of a lint finding. */
    public enum Kind {
        /** Text is empty or whitespace only. */
        BLANK,
        /** Text exceeds its length limit. */
        TOO_LONG,
        /** Text contains a secret-looking value. */
        SECRET
    }

    /**
     * One finding. The detail never contains the checked text itself.
     *
     * @param kind   finding kind
     * @param detail safe human-readable detail (limit, pattern name)
     */
    public record Finding(Kind kind, String detail) {

        /** Validates components. */
        public Finding {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(detail, "detail");
        }
    }

    private final SecretScanner secrets;

    /**
     * Creates the lint with a secret scanner.
     *
     * @param secrets the secret scanner
     */
    public TextLint(SecretScanner secrets) {
        this.secrets = Objects.requireNonNull(secrets, "secrets");
    }

    /**
     * Lint with the built-in secret patterns.
     *
     * @return a default lint
     */
    public static TextLint defaults() {
        return new TextLint(new SecretScanner());
    }

    /**
     * Checks one text.
     *
     * @param text      the text
     * @param maxLength maximum allowed length in chars
     * @return findings, empty if the text is acceptable
     */
    public List<Finding> check(String text, int maxLength) {
        List<Finding> findings = new ArrayList<>(2);
        if (text.isBlank()) {
            findings.add(new Finding(Kind.BLANK, "text is blank"));
            return findings;
        }
        if (text.length() > maxLength) {
            findings.add(new Finding(Kind.TOO_LONG, "length " + text.length() + " exceeds " + maxLength));
        }
        secrets.findSecret(text).ifPresent(p -> findings.add(new Finding(Kind.SECRET, "matches secret pattern " + p)));
        return findings;
    }

    /**
     * Checks a keyword list (count, per-keyword length, secrets).
     *
     * @param keywords the keywords
     * @return findings, empty if acceptable
     */
    public List<Finding> checkKeywords(List<String> keywords) {
        List<Finding> findings = new ArrayList<>();
        if (keywords.size() > MAX_KEYWORDS) {
            findings.add(new Finding(Kind.TOO_LONG, keywords.size() + " keywords exceed " + MAX_KEYWORDS));
        }
        for (String keyword : keywords) {
            findings.addAll(check(keyword, MAX_KEYWORD_LENGTH));
        }
        return findings;
    }
}
