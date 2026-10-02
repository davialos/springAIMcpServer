package com.springaimcpservercommon.ai.safety;

import com.springaimcpservercommon.ai.agent.AgentDefinition;
import com.springaimcpservercommon.ai.agent.OutputSpec;
import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.catalog.MetadataRegistry;
import com.springaimcpservercommon.core.display.SensitiveFields;
import com.springaimcpservercommon.core.display.StructuredResponse;
import com.springaimcpservercommon.core.display.StructuredResponseRenderer;
import com.springaimcpservercommon.core.guard.InputValidationPolicy;
import com.springaimcpservercommon.core.guard.PiiRedactor;
import com.springaimcpservercommon.core.guard.PiiType;
import com.springaimcpservercommon.core.guard.PromptValidationRequest;
import com.springaimcpservercommon.core.guard.PromptValidator;
import com.springaimcpservercommon.core.guard.PromptVerdict;
import com.springaimcpservercommon.core.guard.StreamingPiiRedactor;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * The guardrails of an agent turn (LLD-06 §8, F-76), shared by the synchronous and the streaming path:
 *
 * <ol>
 *   <li><b>Before the model</b> — {@link #validatePrompt} runs the {@link PromptValidator}s (malicious content,
 *       business scope against the effective catalog, host validators) under the agent's policy combined with the
 *       host floor; {@link #prepareInput} redacts personal data from the prompt when the agent or host asks for it,
 *       so it never reaches the model provider or the chat memory.</li>
 *   <li><b>Before the user</b> — {@link #outputGuard} returns a per-turn {@link OutputGuard} that redacts personal
 *       data from the answer (chunk-safe for streams), enforces {@code maxOutputChars} and renders the structured,
 *       backend-controlled {@link StructuredResponse}.</li>
 * </ol>
 *
 * <p>Immutable and thread-safe; one instance per invoker. {@link #disabled()} keeps the pre-F-76 behaviour.
 */
public final class TurnSafety {

    private static final Logger LOG = LoggerFactory.getLogger(TurnSafety.class);

    /** Suffix of an answer cut at {@code maxOutputChars}. */
    public static final String TRUNCATED = " …[truncated]";

    /**
     * Host-level settings ({@code dynamic.ai.agent.guardrails.*}); each is a floor an agent can tighten, not loosen.
     *
     * @param inputFloor        prompt validation applied to every agent
     * @param redactInputPii    redact personal data from every prompt before the model sees it
     * @param redactOutputPii   redact personal data from every answer before the user sees it
     * @param structuredDisplay render a structured display tree for every answer
     */
    public record Settings(InputValidationPolicy inputFloor, boolean redactInputPii, boolean redactOutputPii,
                           boolean structuredDisplay) {

        /** Nothing enabled. */
        public static final Settings OFF = new Settings(InputValidationPolicy.OFF, false, false, false);

        /** Validates components. */
        public Settings {
            Objects.requireNonNull(inputFloor, "inputFloor");
        }
    }

    /**
     * A rejected prompt.
     *
     * @param code    stable code in {@code snake_case}
     * @param message caller-safe explanation
     */
    public record Rejection(String code, String message) {
    }

    private static final TurnSafety DISABLED = new TurnSafety(r -> PromptVerdict.allow(), PiiRedactor.defaults(),
            null, Settings.OFF);

    private final PromptValidator validator;
    private final PiiRedactor redactor;
    private final StructuredResponseRenderer renderer;
    private final @Nullable MetadataRegistry registry;
    private final Settings settings;

    /**
     * Creates the guardrails.
     *
     * @param validator validator chain (built-in plus host validators)
     * @param redactor  PII redactor (built-in plus host detectors)
     * @param registry  effective catalog, for business scope and sensitive keys; {@code null} disables both
     * @param settings  host-level floor
     */
    public TurnSafety(PromptValidator validator, PiiRedactor redactor, @Nullable MetadataRegistry registry,
                      Settings settings) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        this.renderer = new StructuredResponseRenderer(redactor);
        this.registry = registry;
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * Guardrails that do nothing (prompt validation, redaction and display all off).
     *
     * @return the disabled instance
     */
    public static TurnSafety disabled() {
        return DISABLED;
    }

    /**
     * The redactor, for callers that store or forward text outside a turn.
     *
     * @return the redactor
     */
    public PiiRedactor redactor() {
        return redactor;
    }

    // ─── Before the model ─────────────────────────────────────────────────────────────────────────

    /**
     * Validates the prompt.
     *
     * @param agent     the agent
     * @param principal the caller
     * @param prompt    the user's message, as typed
     * @return the rejection, or {@code null} when the prompt may proceed
     */
    public @Nullable Rejection validatePrompt(AgentDefinition agent, DaiPrincipal principal, String prompt) {
        if (this == DISABLED) {
            return null;
        }
        InputValidationPolicy policy = agent.guardrails().inputValidation().strictest(settings.inputFloor());
        // always run the chain: the built-in validators read the policy and pass when their check is off, while a
        // host's own PromptValidator beans must run whatever the built-in switches say
        PromptValidationRequest request = new PromptValidationRequest(prompt, agent.workspaceId(), agent.slug(),
                principal, policy, agent.guardrails().topicAllowList(), this::catalog);
        PromptVerdict verdict = validator.validate(request);
        if (verdict instanceof PromptVerdict.Rejected r) {
            LOG.info("Agent {} prompt rejected for principal {}: {} {}", agent.slug(), principal.principalId(),
                    r.code(), r.findings());
            return new Rejection(r.code(), r.message());
        }
        return null;
    }

    /**
     * The prompt as sent to the model: personal data redacted when the agent or the host asks for it.
     *
     * @param agent  the agent
     * @param prompt the user's message
     * @return the prompt to send
     */
    public String prepareInput(AgentDefinition agent, String prompt) {
        if (!(agent.guardrails().piiRedactionInput() || settings.redactInputPii())) {
            return prompt;
        }
        return redactor.redact(prompt).text();
    }

    // ─── Before the user ──────────────────────────────────────────────────────────────────────────

    /**
     * Guard for one turn's answer.
     *
     * @param agent the agent
     * @return a new guard (not thread-safe; one per turn)
     */
    public OutputGuard outputGuard(AgentDefinition agent) {
        boolean redact = agent.guardrails().piiRedactionOutput() || settings.redactOutputPii();
        return new OutputGuard(agent, redact, settings.structuredDisplay());
    }

    private EffectiveCatalog catalog() {
        if (registry == null) {
            throw new IllegalStateException("no metadata registry");
        }
        return registry.current();
    }

    private SensitiveFields sensitiveFields() {
        if (registry == null) {
            return SensitiveFields.heuristic();
        }
        try {
            return SensitiveFields.of(registry.current());
        } catch (RuntimeException e) {
            LOG.warn("Effective catalog unavailable for display masking; using the name heuristic only", e);
            return SensitiveFields.heuristic();
        }
    }

    /**
     * Guards one answer: redaction (whole or chunk by chunk), the {@code maxOutputChars} limit and the structured
     * display. For a stream call {@link #onChunk} per chunk and {@link #finishStream} once; for a synchronous turn
     * call {@link #finish} once.
     */
    public final class OutputGuard {

        private final AgentDefinition agent;
        private final boolean redact;
        private final boolean display;
        private final boolean structured;
        private final int maxChars;
        private final @Nullable StreamingPiiRedactor stream;
        private final Map<PiiType, Integer> counts = new EnumMap<>(PiiType.class);
        private int emitted;
        private boolean suffixSent;

        private OutputGuard(AgentDefinition agent, boolean redact, boolean display) {
            this.agent = agent;
            this.redact = redact;
            this.display = display;
            this.structured = agent.output().mode() == OutputSpec.Mode.JSON_SCHEMA;
            this.maxChars = structured ? 0 : agent.guardrails().maxOutputChars();
            this.stream = redact && !structured ? new StreamingPiiRedactor(redactor) : null;
        }

        /**
         * Whether this guard changes anything (otherwise callers may skip it).
         *
         * @return {@code true} if redaction, a length limit or the display is active
         */
        public boolean active() {
            return redact || maxChars > 0 || display;
        }

        /**
         * A streamed text chunk: returns what may be sent now (possibly empty).
         *
         * @param chunk raw chunk from the model
         * @return text to send now
         */
        public String onChunk(String chunk) {
            String out = stream != null ? stream.push(chunk) : chunk;
            return limit(out);
        }

        /**
         * End of a streamed text answer: returns the rest to send (possibly empty).
         *
         * @return remaining text
         */
        public String finishStream() {
            return limit(stream != null ? stream.flush() : "");
        }

        /**
         * A complete answer (synchronous turn, or a held-back structured stream): redacted and limited.
         *
         * @param answer raw answer
         * @return the answer to send
         */
        public String finish(String answer) {
            String out = answer;
            if (redact) {
                if (structured) {
                    StructuredResponseRenderer.RedactedJson r = renderer.redactJson(answer, sensitiveFields());
                    r.counts().forEach((t, n) -> counts.merge(t, n, Integer::sum));
                    out = r.text();
                } else {
                    PiiRedactor.Redaction r = redactor.redact(answer);
                    r.counts().forEach((t, n) -> counts.merge(t, n, Integer::sum));
                    out = r.text();
                }
            }
            if (maxChars > 0 && out.length() > maxChars) {
                return out.substring(0, maxChars) + TRUNCATED;
            }
            return out;
        }

        /**
         * The structured display of the answer, or {@code null} when the display is off or rendering failed (the
         * text answer is still delivered; the failure is logged without content).
         *
         * @param rawAnswer the model's answer before redaction (the renderer redacts every value itself)
         * @return the display tree, or {@code null}
         */
        public @Nullable StructuredResponse display(String rawAnswer) {
            if (!display || rawAnswer.isBlank()) {
                return null;
            }
            try {
                String source = maxChars > 0 && rawAnswer.length() > maxChars
                        ? rawAnswer.substring(0, maxChars) : rawAnswer;
                return renderer.render(agent.output().display(), source, sensitiveFields());
            } catch (RuntimeException e) {
                LOG.warn("Structured display failed for agent {}; the answer is sent as text only", agent.slug(), e);
                return null;
            }
        }

        /**
         * Personal-data values removed from the answer so far, per type.
         *
         * @return counts
         */
        public Map<PiiType, Integer> redactions() {
            Map<PiiType, Integer> all = new EnumMap<>(PiiType.class);
            all.putAll(counts);
            if (stream != null) {
                stream.counts().forEach((t, n) -> all.merge(t, n, Integer::sum));
            }
            return Map.copyOf(all);
        }

        private String limit(String out) {
            if (maxChars <= 0 || out.isEmpty()) {
                return out;
            }
            if (emitted >= maxChars) {
                if (suffixSent) {
                    return "";
                }
                suffixSent = true;
                return TRUNCATED;
            }
            int room = maxChars - emitted;
            if (out.length() > room) {
                emitted = maxChars;
                suffixSent = true;
                return out.substring(0, room) + TRUNCATED;
            }
            emitted += out.length();
            return out;
        }
    }
}
