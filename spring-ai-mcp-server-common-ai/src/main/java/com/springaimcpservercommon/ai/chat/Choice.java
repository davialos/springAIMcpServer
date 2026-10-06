package com.springaimcpservercommon.ai.chat;

import com.springaimcpservercommon.core.display.AnswerContent;
import com.springaimcpservercommon.core.guard.PiiRedactor;
import com.springaimcpservercommon.core.json.CanonicalJson;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A question with options shown to the user as a {@code choice} component (LLD-13 §3), and the validation of the
 * user's answer against it. The payload is the contract with the client:
 *
 * <pre>{@code
 * {"componentId": "c1", "question": "Which order do you mean?", "multiple": false, "allowOther": true,
 *  "options": [{"value": "PO-1", "label": "PO-1 (open)", "description": "Placed 2 Oct"}, …]}
 * }</pre>
 *
 * @param componentId id within the turn
 * @param question    the question (≤ {@value #MAX_QUESTION} chars)
 * @param options     2–{@value #MAX_OPTIONS} options with unique values
 * @param multiple    whether several options may be picked
 * @param allowOther  whether the user may type an answer of their own instead
 */
public record Choice(String componentId, String question, List<Option> options, boolean multiple,
                     boolean allowOther) {

    /** Component type sent in {@code ui.component}. */
    public static final String TYPE = "choice";
    /** Most options. */
    public static final int MAX_OPTIONS = 12;
    /** Longest question. */
    public static final int MAX_QUESTION = 500;
    /** Longest label or value. */
    public static final int MAX_LABEL = 200;
    /** Longest option description. */
    public static final int MAX_DESCRIPTION = 300;
    /** Longest free-text ("other") answer. */
    public static final int MAX_OTHER = 1_000;

    /**
     * One option.
     *
     * @param value       value returned when picked
     * @param label       text shown on the option
     * @param description optional secondary text
     */
    public record Option(String value, String label, @Nullable String description) {
        /** Validates the components. */
        public Option {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(label, "label");
        }
    }

    /** Validates and copies. */
    public Choice {
        Objects.requireNonNull(componentId, "componentId");
        Objects.requireNonNull(question, "question");
        options = List.copyOf(options);
    }

    /** The request is not a valid choice; the message is safe to return to the model. */
    public static final class InvalidChoiceException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        InvalidChoiceException(String message) {
            super(message);
        }
    }

    /**
     * Builds a choice from the model's tool arguments, redacting personal data from every text.
     *
     * @param componentId id within the turn
     * @param argsJson    tool arguments {@code {question, options[], multiple?, allowOther?}}
     * @param redactor    PII redactor applied to question, labels and descriptions
     * @return the choice
     * @throws InvalidChoiceException when the arguments are not a valid choice
     */
    public static Choice fromToolArguments(String componentId, String argsJson, PiiRedactor redactor) {
        if (!(AnswerContent.parseJson(argsJson) instanceof Map<?, ?> args)) {
            throw new InvalidChoiceException("arguments must be a JSON object");
        }
        String question = text(args.get("question"), "question", MAX_QUESTION, true, redactor);
        if (!(args.get("options") instanceof List<?> raw) || raw.size() < 2 || raw.size() > MAX_OPTIONS) {
            throw new InvalidChoiceException("options must be an array of 2 to " + MAX_OPTIONS + " items");
        }
        List<Option> options = new ArrayList<>();
        Set<String> values = new HashSet<>();
        for (Object o : raw) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new InvalidChoiceException("every option must be an object");
            }
            String label = text(m.get("label"), "label", MAX_LABEL, true, redactor);
            Object rawValue = m.get("value");
            String value = rawValue == null ? label : text(rawValue, "value", MAX_LABEL, true, redactor);
            if (!values.add(value)) {
                throw new InvalidChoiceException("option values must be unique: " + value);
            }
            options.add(new Option(value, label, text(m.get("description"), "description", MAX_DESCRIPTION, false,
                    redactor)));
        }
        return new Choice(componentId, question, options, Boolean.TRUE.equals(args.get("multiple")),
                Boolean.TRUE.equals(args.get("allowOther")));
    }

    private static @Nullable String text(@Nullable Object raw, String field, int max, boolean required,
                                         PiiRedactor redactor) {
        if (raw == null || (raw instanceof String s && s.isBlank())) {
            if (required) {
                throw new InvalidChoiceException(field + " is required");
            }
            return null;
        }
        if (!(raw instanceof String) && !(raw instanceof Number) && !(raw instanceof Boolean)) {
            throw new InvalidChoiceException(field + " must be a string");
        }
        String s = String.valueOf(raw).strip();
        if (s.length() > max) {
            throw new InvalidChoiceException(field + " must be at most " + max + " characters");
        }
        return redactor.redact(s).text();
    }

    /**
     * The payload sent to the client and stored with the component.
     *
     * @return canonical JSON
     */
    public String toPayloadJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("componentId", componentId);
        m.put("question", question);
        m.put("multiple", multiple);
        m.put("allowOther", allowOther);
        List<Object> opts = new ArrayList<>();
        for (Option o : options) {
            Map<String, Object> om = new LinkedHashMap<>();
            om.put("value", o.value());
            om.put("label", o.label());
            if (o.description() != null) {
                om.put("description", o.description());
            }
            opts.add(om);
        }
        m.put("options", opts);
        return CanonicalJson.write(m);
    }

    /**
     * Reads a stored payload back.
     *
     * @param payloadJson payload written by {@link #toPayloadJson()}
     * @return the choice
     * @throws InvalidChoiceException when the payload is not a choice
     */
    public static Choice fromPayloadJson(String payloadJson) {
        if (!(AnswerContent.parseJson(payloadJson) instanceof Map<?, ?> m)
                || !(m.get("componentId") instanceof String id) || !(m.get("question") instanceof String q)
                || !(m.get("options") instanceof List<?> raw)) {
            throw new InvalidChoiceException("not a choice payload");
        }
        List<Option> options = new ArrayList<>();
        for (Object o : raw) {
            if (o instanceof Map<?, ?> om && om.get("value") instanceof String v && om.get("label") instanceof String l) {
                options.add(new Option(v, l, om.get("description") instanceof String d ? d : null));
            }
        }
        return new Choice(id, q, options, Boolean.TRUE.equals(m.get("multiple")),
                Boolean.TRUE.equals(m.get("allowOther")));
    }

    /**
     * A validated answer.
     *
     * @param values selected option values, in option order
     * @param labels their labels
     * @param other  free-text answer, if the choice allows one and the user typed it
     */
    public record Answer(List<String> values, List<String> labels, @Nullable String other) {

        /** Copies the lists. */
        public Answer {
            values = List.copyOf(values);
            labels = List.copyOf(labels);
        }

        /**
         * The answer as stored and returned by the UI-state API.
         *
         * @return canonical JSON {@code {values, labels, other?}}
         */
        public String toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("values", values);
            m.put("labels", labels);
            if (other != null) {
                m.put("other", other);
            }
            return CanonicalJson.write(m);
        }

        /**
         * The message the model receives when the user answers without typing anything else.
         *
         * @param question the question asked
         * @return e.g. {@code Selected: PO-1 (open)}
         */
        public String asMessage(String question) {
            List<String> parts = new ArrayList<>(labels);
            if (other != null) {
                parts.add(other);
            }
            return "My answer to \"" + question + "\": " + String.join(", ", parts);
        }
    }

    /**
     * Validates the user's answer: values must be options of this choice, one value unless {@code multiple}, free
     * text only when {@code allowOther}, and at least one of them.
     *
     * @param values selected values (may be empty when {@code other} is given)
     * @param other  free text, or {@code null}
     * @param redactor PII redactor applied to the free text
     * @return the answer
     * @throws InvalidChoiceException when the answer does not fit the choice
     */
    public Answer validate(List<String> values, @Nullable String other, PiiRedactor redactor) {
        String typed = other == null || other.isBlank() ? null : other.strip();
        if (typed != null && !allowOther) {
            throw new InvalidChoiceException("this question does not accept a free-text answer");
        }
        if (typed != null && typed.length() > MAX_OTHER) {
            throw new InvalidChoiceException("the free-text answer must be at most " + MAX_OTHER + " characters");
        }
        Set<String> picked = new HashSet<>(values);
        if (picked.size() != values.size()) {
            throw new InvalidChoiceException("an option was selected twice");
        }
        if (picked.isEmpty() && typed == null) {
            throw new InvalidChoiceException("select an option");
        }
        if (!multiple && picked.size() + (typed == null ? 0 : 1) > 1) {
            throw new InvalidChoiceException("only one option can be selected");
        }
        List<String> orderedValues = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (Option o : options) {
            if (picked.remove(o.value())) {
                orderedValues.add(o.value());
                labels.add(o.label());
            }
        }
        if (!picked.isEmpty()) {
            throw new InvalidChoiceException("unknown option");
        }
        return new Answer(orderedValues, labels, typed == null ? null : redactor.redact(typed).text());
    }
}
