package com.springaimcpservercommon.core.guard;

/**
 * SPI: judges a user prompt before it is sent to a model (LLD-06 §8, F-76).
 *
 * <p>The library runs {@link MaliciousPromptValidator} and {@link BusinessScopeValidator}; a host adds its own
 * rules (a regulated-advice filter, a customer-specific block list, a call to a moderation service) by declaring a
 * {@code PromptValidator} bean. All validators run in order (host validators by {@code @Order}, after the built-in
 * ones) and the first rejection wins. A validator that throws rejects the turn (fail closed).
 *
 * <p>Implementations must be thread-safe, must not log the prompt and should be fast: they run on every turn, before
 * the stream opens. A validator that calls a remote service must bound the call with a timeout (LLD-14).
 */
@FunctionalInterface
public interface PromptValidator {

    /**
     * Judges one prompt.
     *
     * @param request the prompt and its context
     * @return {@link PromptVerdict#allow()} or a rejection
     */
    PromptVerdict validate(PromptValidationRequest request);

    /**
     * Name used in logs and in {@code input_validation_failed} findings.
     *
     * @return a short name
     */
    default String name() {
        return getClass().getSimpleName();
    }
}
