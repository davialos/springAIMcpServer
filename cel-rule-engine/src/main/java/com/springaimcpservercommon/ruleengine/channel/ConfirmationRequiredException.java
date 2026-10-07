package com.springaimcpservercommon.ruleengine.channel;

/**
 * Thrown when the user must confirm something before the action can proceed (an external API): the UI shows the
 * message in a pop-up and repeats the request with {@code confirmExternal=true}.
 */
public class ConfirmationRequiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public ConfirmationRequiredException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * @return a machine-readable code the UI keys on
     */
    public String code() {
        return code;
    }
}
