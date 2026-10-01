package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.guard.PiiDetector;
import com.springaimcpservercommon.core.guard.PiiMatch;
import com.springaimcpservercommon.core.guard.PiiRedactor;
import com.springaimcpservercommon.core.guard.PiiType;
import com.springaimcpservercommon.core.guard.RegexPiiDetector;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * How personal data is handled in stored conversation text (F-76, OQ-44): the transcript, its audit-retained copy
 * and the model's chat memory. Built on the same {@link PiiDetector} SPI and {@link PiiRedactor} as the prompt and
 * answer guardrails, so a host detector bean covers storage too; only the selection of built-in types and the
 * {@link DaiPiiProperties.Mode} are storage-specific.
 *
 * @param redactor the redactor, or {@code null} when the mode is {@code OFF}
 * @param mode     what to do with a message that contains personal data
 */
@NullMarked
record ConversationPii(@Nullable PiiRedactor redactor, DaiPiiProperties.Mode mode) {

    /** Built-in types masked in storage unless {@code types} is set: everything but IP addresses and credentials. */
    static final Set<PiiType> DEFAULT_TYPES = Set.copyOf(EnumSet.of(PiiType.EMAIL, PiiType.PHONE,
            PiiType.CREDIT_CARD, PiiType.IBAN, PiiType.NATIONAL_ID));

    /** Compact constructor: a non-{@code OFF} mode needs a redactor. */
    ConversationPii {
        Objects.requireNonNull(mode, "mode");
        if (mode != DaiPiiProperties.Mode.OFF && redactor == null) {
            throw new IllegalArgumentException("mode " + mode + " needs a redactor");
        }
    }

    /**
     * No masking of personal data (credentials are still removed by the {@link MessageRedactor}).
     *
     * @return the policy
     */
    static ConversationPii off() {
        return new ConversationPii(null, DaiPiiProperties.Mode.OFF);
    }

    /**
     * The policy for the given settings: the built-in detector restricted to the configured types, the host's
     * custom patterns (reported as {@link PiiType#OTHER}) and every host {@link PiiDetector} bean.
     *
     * @param props         {@code dynamic.ai.agent.conversations.pii.*}
     * @param hostDetectors host detector beans, in {@code @Order} order
     * @return the policy
     * @throws IllegalStateException when a custom pattern is not a valid regular expression
     */
    static ConversationPii of(DaiPiiProperties props, List<PiiDetector> hostDetectors) {
        if (props.mode() == DaiPiiProperties.Mode.OFF) {
            return off();
        }
        Set<PiiType> types = props.types().isEmpty() ? DEFAULT_TYPES : Set.copyOf(props.types());
        RegexPiiDetector builtIn = new RegexPiiDetector();
        List<PiiDetector> detectors = new ArrayList<>();
        detectors.add(text -> builtIn.detect(text).stream().filter(m -> types.contains(m.type())).toList());
        List<Pattern> custom = new ArrayList<>();
        for (Map.Entry<String, String> e : props.customPatterns().entrySet()) {
            try {
                custom.add(Pattern.compile(e.getValue()));
            } catch (PatternSyntaxException ex) {
                throw new IllegalStateException("dynamic.ai.agent.conversations.pii.custom-patterns." + e.getKey()
                        + " is not a valid regular expression");
            }
        }
        if (!custom.isEmpty()) {
            detectors.add(text -> {
                List<PiiMatch> out = new ArrayList<>();
                for (Pattern p : custom) {
                    p.matcher(text).results().filter(r -> r.end() > r.start())
                            .forEach(r -> out.add(new PiiMatch(PiiType.OTHER, r.start(), r.end())));
                }
                return out;
            });
        }
        detectors.addAll(hostDetectors);
        return new ConversationPii(new PiiRedactor(detectors), props.mode());
    }
}
