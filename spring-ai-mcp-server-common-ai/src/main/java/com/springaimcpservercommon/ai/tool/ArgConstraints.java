package com.springaimcpservercommon.ai.tool;

import com.springaimcpservercommon.core.json.CanonicalJson;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Applies a binding's {@link ArgConstraint}s to the arguments a model sent (LLD-07 §3): the server decides these
 * values, never the model.
 * <ul>
 *   <li>{@code PRINCIPAL_ATTR}: the argument is overwritten with the caller's attribute; a caller without the
 *       attribute is refused (fail closed);</li>
 *   <li>{@code LITERAL}: the argument is overwritten with the configured value;</li>
 *   <li>{@code RANGE}: a present argument must be a number within the range, otherwise the call is refused so the
 *       model can correct itself; an absent argument is left to the tool's own default.</li>
 * </ul>
 * Refusal messages name the argument and the rule, never a caller attribute value.
 */
@NullMarked
final class ArgConstraints {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Outcome of applying the constraints. */
    sealed interface Result permits Applied, Rejected {}

    /**
     * The arguments to run the tool with.
     *
     * @param input effective tool input (canonical JSON)
     */
    record Applied(String input) implements Result {}

    /**
     * The call must not run.
     *
     * @param code    stable error code
     * @param message safe message for the model
     */
    record Rejected(String code, String message) implements Result {}

    private ArgConstraints() {
    }

    static Result apply(Map<String, ArgConstraint> constraints, DaiPrincipal principal, @Nullable String toolInput) {
        if (constraints.isEmpty()) {
            return new Applied(toolInput == null ? "{}" : toolInput);
        }
        Map<String, Object> args = new LinkedHashMap<>();
        if (toolInput != null && !toolInput.isBlank()) {
            Object parsed;
            try {
                parsed = MAPPER.readerFor(Object.class).readValue(toolInput);
            } catch (RuntimeException e) {
                return new Rejected("invalid_arguments", "The tool arguments are not valid JSON.");
            }
            if (!(parsed instanceof Map<?, ?> map)) {
                return new Rejected("invalid_arguments", "The tool arguments must be a JSON object.");
            }
            map.forEach((k, v) -> args.put(String.valueOf(k), v));
        }
        for (Map.Entry<String, ArgConstraint> entry : constraints.entrySet()) {
            String name = entry.getKey();
            ArgConstraint constraint = entry.getValue();
            switch (constraint.kind()) {
                case PRINCIPAL_ATTR -> {
                    Object value = principal.attributes().get(constraint.principalAttr());
                    if (value == null) {
                        return new Rejected("constraint_unsatisfied",
                                "Argument '" + name + "' is fixed by the caller's identity, which lacks it.");
                    }
                    args.put(name, value);
                }
                case LITERAL -> args.put(name, constraint.literalValue());
                case RANGE -> {
                    Object value = args.get(name);
                    if (value == null) {
                        break;
                    }
                    Number min = constraint.minValue();
                    Number max = constraint.maxValue();
                    if (!(value instanceof Number number) || min == null || max == null
                            || number.doubleValue() < min.doubleValue() || number.doubleValue() > max.doubleValue()) {
                        return new Rejected("out_of_range", "Argument '" + name + "' must be a number between "
                                + min + " and " + max + ".");
                    }
                }
            }
        }
        try {
            return new Applied(CanonicalJson.write(args));
        } catch (IllegalArgumentException e) {
            return new Rejected("invalid_arguments", "The tool arguments could not be processed.");
        }
    }
}
