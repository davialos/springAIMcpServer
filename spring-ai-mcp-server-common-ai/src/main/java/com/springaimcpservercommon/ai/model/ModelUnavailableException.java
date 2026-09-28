package com.springaimcpservercommon.ai.model;

/**
 * Thrown by {@link ModelRouter#resolve} when no suitable {@link org.springframework.ai.chat.model.ChatModel}
 * is available, including when the primary and all fallback providers are unreachable.
 */
public final class ModelUnavailableException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param providerId the provider id that could not be resolved
     * @param cause      the underlying cause, if any
     */
    public ModelUnavailableException(String providerId, Throwable cause) {
        super("No ChatModel available for provider '" + providerId + "'", cause);
    }

    /**
     * Creates the exception without a cause.
     *
     * @param providerId the provider id that could not be resolved
     */
    public ModelUnavailableException(String providerId) {
        super("No ChatModel available for provider '" + providerId + "'");
    }
}
