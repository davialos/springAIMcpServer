package com.springaimcpservercommon.core.guard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Runs several {@link PromptValidator}s in order; the first rejection wins. A validator that throws rejects the
 * prompt with {@code input_validation_failed} (fail closed) and the failure is logged with the validator's name,
 * never with the prompt.
 *
 * <p>Deliberately not itself registered as a {@code PromptValidator} bean: the auto-configuration builds it from the
 * built-in validators plus the host's beans.
 */
public final class CompositePromptValidator implements PromptValidator {

    private static final Logger LOG = LoggerFactory.getLogger(CompositePromptValidator.class);

    private final List<PromptValidator> validators;

    /**
     * Creates the composite.
     *
     * @param validators validators in the order they run
     */
    public CompositePromptValidator(List<? extends PromptValidator> validators) {
        this.validators = List.copyOf(validators);
    }

    /**
     * The built-in validators: malicious-content detection, then business scope.
     *
     * @return composite of the built-in validators
     */
    public static CompositePromptValidator defaults() {
        return new CompositePromptValidator(List.of(new MaliciousPromptValidator(), new BusinessScopeValidator()));
    }

    /**
     * The validators, in order.
     *
     * @return the validators
     */
    public List<PromptValidator> validators() {
        return validators;
    }

    @Override
    public PromptVerdict validate(PromptValidationRequest request) {
        for (PromptValidator validator : validators) {
            PromptVerdict verdict;
            try {
                verdict = validator.validate(request);
            } catch (RuntimeException e) {
                LOG.warn("Prompt validator {} failed for agent {}; the prompt is rejected",
                        validator.name(), request.agentSlug(), e);
                return PromptVerdict.reject("input_validation_failed",
                        "Your message could not be checked right now. Please try again later.",
                        List.of(validator.name()));
            }
            if (verdict == null) {
                LOG.warn("Prompt validator {} returned no verdict for agent {}; the prompt is rejected",
                        validator.name(), request.agentSlug());
                return PromptVerdict.reject("input_validation_failed",
                        "Your message could not be checked right now. Please try again later.",
                        List.of(validator.name()));
            }
            if (!verdict.allowed()) {
                return verdict;
            }
        }
        return PromptVerdict.allow();
    }

    @Override
    public String name() {
        return "composite";
    }
}
