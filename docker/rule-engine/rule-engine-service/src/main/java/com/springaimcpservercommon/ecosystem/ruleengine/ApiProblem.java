package com.springaimcpservercommon.ecosystem.ruleengine;

import org.springframework.http.HttpStatus;

/** A refused request: HTTP status, a stable machine code and a message safe to show (never a fact value). */
public final class ApiProblem extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    /**
     * Creates the problem.
     *
     * @param status  HTTP status
     * @param code    stable code, e.g. {@code invalid_expression}
     * @param message detail
     */
    public ApiProblem(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** @return HTTP status */
    public HttpStatus status() {
        return status;
    }

    /** @return stable machine code */
    public String code() {
        return code;
    }

    /** 400. */
    public static ApiProblem bad(String code, String message) {
        return new ApiProblem(HttpStatus.BAD_REQUEST, code, message);
    }

    /** 403. */
    public static ApiProblem forbidden(String code, String message) {
        return new ApiProblem(HttpStatus.FORBIDDEN, code, message);
    }

    /** 404. */
    public static ApiProblem notFound(String what) {
        return new ApiProblem(HttpStatus.NOT_FOUND, "not_found", what + " not found");
    }

    /** 409. */
    public static ApiProblem conflict(String code, String message) {
        return new ApiProblem(HttpStatus.CONFLICT, code, message);
    }

    /** 422. */
    public static ApiProblem invalid(String code, String message) {
        return new ApiProblem(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
