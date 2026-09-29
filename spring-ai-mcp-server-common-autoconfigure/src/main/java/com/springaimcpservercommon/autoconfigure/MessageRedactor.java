package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.lint.SecretScanner;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Prepares a message for storage in a conversation transcript (LLD-06 §7: "stored after redaction").
 *
 * <p>A message that contains a credential (private key, cloud or API token, JWT, URL with credentials, password
 * assignment; the {@link SecretScanner} defaults) is <em>replaced</em> by a placeholder and flagged
 * {@code redacted}, because masking only the matched part of free text is not reliable; the credential never
 * reaches the store. Longer messages are cut at the storage limit. Configurable PII detectors (F-76) are not
 * implemented yet (OQ-44).
 */
@NullMarked
final class MessageRedactor {

    /** Placeholder stored instead of a message that contained a credential. */
    static final String REMOVED = "[message removed: it contained a credential]";
    /** Suffix appended to a message cut at the storage limit. */
    static final String TRUNCATED = " …[truncated]";

    /**
     * A message ready to store.
     *
     * @param content  the text to store
     * @param redacted whether the original was replaced because it contained a credential
     */
    record Redacted(String content, boolean redacted) {}

    private final SecretScanner scanner;
    private final int maxChars;

    MessageRedactor(SecretScanner scanner, int maxChars) {
        this.scanner = Objects.requireNonNull(scanner, "scanner");
        if (maxChars < 100) {
            throw new IllegalArgumentException("maxChars must be at least 100");
        }
        this.maxChars = maxChars;
    }

    /**
     * Redacts and bounds a message.
     *
     * @param text the raw message
     * @return the text to store
     */
    Redacted apply(String text) {
        if (scanner.findSecret(text).isPresent()) {
            return new Redacted(REMOVED, true);
        }
        if (text.length() > maxChars) {
            return new Redacted(text.substring(0, maxChars - TRUNCATED.length()) + TRUNCATED, false);
        }
        return new Redacted(text, false);
    }

    /**
     * A short single-line title from the first user message, or {@code null} when the message was removed.
     *
     * @param stored the stored form of the first user message
     * @return title of at most 80 characters, or {@code null}
     */
    @Nullable String title(Redacted stored) {
        if (stored.redacted()) {
            return null;
        }
        String oneLine = stored.content().replaceAll("\\s+", " ").strip();
        if (oneLine.isEmpty()) {
            return null;
        }
        return oneLine.length() <= 80 ? oneLine : oneLine.substring(0, 79) + "…";
    }
}
