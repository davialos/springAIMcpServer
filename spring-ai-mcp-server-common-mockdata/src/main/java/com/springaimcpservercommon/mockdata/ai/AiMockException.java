package com.springaimcpservercommon.mockdata.ai;

/** The AI model could not produce usable mock objects (provider error, timeout, unparsable answer). */
public class AiMockException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what went wrong; never contains generated data or prompts
     * @param cause   the underlying failure, may be {@code null}
     */
    public AiMockException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates the exception.
     *
     * @param message what went wrong; never contains generated data or prompts
     */
    public AiMockException(String message) {
        super(message);
    }
}
