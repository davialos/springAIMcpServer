package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.lint.RegexPiiDetector;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Map;

/**
 * Personal data in stored conversation text (F-76, OQ-44), bound under {@code dynamic.ai.agent.conversations.pii}.
 * Applies to what is stored: the user-visible transcript, the audit-retained copy and the model's chat memory. It does
 * not change what is sent to the model in the current turn. Credentials are always removed, this is about personal data.
 *
 * @param mode           {@code MASK} (default) replaces each match with its label, for example {@code [EMAIL]};
 *                       {@code REMOVE} replaces the whole message; {@code OFF} stores text as it is
 * @param types          built-in detectors; default e-mail, phone, card number, IBAN, US SSN (IPV4 is opt-in)
 * @param customPatterns the host's own patterns, label (UPPER_SNAKE_CASE) to Java regex, for example
 *                       {@code EMPLOYEE_ID: "E-\\d{6}"}
 */
@ConfigurationProperties(prefix = "dynamic.ai.agent.conversations.pii")
public record DaiPiiProperties(
        @DefaultValue("MASK") Mode mode,
        @DefaultValue List<RegexPiiDetector.Type> types,
        @DefaultValue Map<String, String> customPatterns) {

    /** What to do with a message that contains personal data. */
    public enum Mode {
        /** Store as it is. */
        OFF,
        /** Replace each match by its label. */
        MASK,
        /** Replace the whole message. */
        REMOVE
    }
}
