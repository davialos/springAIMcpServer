package com.springaimcpservercommon.ruleengine.api;

import org.springframework.http.HttpStatus;

/** An error the caller can act on: an HTTP status and a machine-readable code. */
public class RuleEngineException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String code;

    public RuleEngineException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /**
     * @param what what was not found
     * @return a 404
     */
    public static RuleEngineException notFound(String what) {
        return new RuleEngineException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " was not found");
    }

    /**
     * @param message what is wrong with the request
     * @return a 400
     */
    public static RuleEngineException badRequest(String message) {
        return new RuleEngineException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    /**
     * @return the HTTP status
     */
    public HttpStatus status() {
        return status;
    }

    /**
     * @return the machine-readable code
     */
    public String code() {
        return code;
    }
}
