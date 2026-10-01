package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.guard.PiiRedactor;
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
 * reaches the store. Personal data found by the {@link ConversationPii} policy (the guardrails' {@link PiiRedactor},
 * F-76) is masked (each match replaced by its typed placeholder, for example {@code [redacted email]}) or the whole
 * message is replaced, per {@link DaiPiiProperties.Mode} (OQ-44).
 * Longer messages are cut at the storage limit.
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
     * @param redacted whether the original was replaced or masked because it contained a credential or personal data
     */
    record Redacted(String content, boolean redacted) {}

    /** Placeholder stored instead of a message that contained personal data, in {@code REMOVE} mode. */
    static final String REMOVED_PII = "[message removed: it contained personal data]";

    private final SecretScanner scanner;
    private final int maxChars;
    private final ConversationPii pii;

    MessageRedactor(SecretScanner scanner, int maxChars) {
        this(scanner, maxChars, ConversationPii.off());
    }

    MessageRedactor(SecretScanner scanner, int maxChars, ConversationPii pii) {
        this.pii = Objects.requireNonNull(pii, "pii");
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
        boolean masked = false;
        PiiRedactor redactor = pii.redactor();
        if (redactor != null) {
            if (pii.mode() == DaiPiiProperties.Mode.REMOVE) {
                // containsPii is true when a detector fails: never let unchecked text through
                if (redactor.containsPii(text)) {
                    return new Redacted(REMOVED_PII, true);
                }
            } else {
                PiiRedactor.Redaction r = redactor.redact(text);
                if (r.withheld()) {
                    return new Redacted(REMOVED_PII, true); // a detector failed: keep nothing of this message
                }
                if (r.changed()) {
                    text = r.text();
                    masked = true;
                }
            }
        }
        if (text.length() > maxChars) {
            return new Redacted(text.substring(0, maxChars - TRUNCATED.length()) + TRUNCATED, masked);
        }
        return new Redacted(text, masked);
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
