package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.guard.PiiType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Map;

/**
 * Personal data in stored conversation text (F-76, OQ-44), bound under {@code dynamic.ai.agent.conversations.pii}.
 * Applies to what is stored: the user-visible transcript, the audit-retained copy and the model's chat memory. It does
 * not change what is sent to the model in the current turn. Credentials are always removed, this is about personal data.
 *
 * Detection is the guardrails' (F-76): the built-in {@code core.guard.RegexPiiDetector} plus every host
 * {@code core.guard.PiiDetector} bean, so one detector bean covers prompts, answers and storage.
 *
 * @param mode           {@code MASK} (default) replaces each match with its typed placeholder, for example
 *                       {@code [redacted email]}; {@code REMOVE} replaces the whole message; {@code OFF} stores text
 *                       as it is
 * @param types          built-in types masked in storage; default {@code EMAIL, PHONE, CREDIT_CARD, IBAN,
 *                       NATIONAL_ID} ({@code IP_ADDRESS} is opt-in; credentials always remove the whole message)
 * @param customPatterns the host's own patterns, a name to a Java regex, for example {@code EMPLOYEE_ID: "E-\\d{6}"};
 *                       matches are masked as {@code [redacted other]}
 */
@ConfigurationProperties(prefix = "dynamic.ai.agent.conversations.pii")
public record DaiPiiProperties(
        @DefaultValue("MASK") Mode mode,
        @DefaultValue List<PiiType> types,
        @DefaultValue Map<String, String> customPatterns) {

    /** What to do with a message that contains personal data. */
    public enum Mode {
        /** Store as it is. */
        OFF,
        /** Replace each match by its typed placeholder. */
        MASK,
        /** Replace the whole message. */
        REMOVE
    }
}
