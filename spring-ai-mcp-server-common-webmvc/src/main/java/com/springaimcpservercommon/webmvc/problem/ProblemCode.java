package com.springaimcpservercommon.webmvc.problem;

/**
 * Stable problem codes for dynamic endpoint errors (LLD-04 §4, RFC 9457).
 *
 * <p>The {@code type} URI is {@code https://dynamic-ai/problems/<code>}.
 */
public enum ProblemCode {

    /** No published endpoint matches this path + method. */
    NOT_FOUND("not-found", 404),

    /** The endpoint or its workspace/global kill switch is active. */
    ENDPOINT_DISABLED("endpoint-disabled", 503),

    /** The endpoint's backing resource (query, agent, operation) is suspended due to catalog drift. */
    RESOURCE_SUSPENDED("resource-suspended", 503),

    /** The caller lacks permission to invoke this endpoint. */
    ACCESS_DENIED("access-denied", 403),

    /** No authenticated caller; clients should (re-)authenticate. */
    UNAUTHENTICATED("unauthenticated", 401),

    /** The request conflicts with the current state of the resource. */
    CONFLICT("conflict", 409),

    /** The caller's expected version (If-Match) is stale. */
    PRECONDITION_FAILED("precondition-failed", 412),

    /** A mutation needs an {@code If-Match} header and none was sent. */
    PRECONDITION_REQUIRED("precondition-required", 428),

    /** The change proposal expired before it was decided. */
    PROPOSAL_EXPIRED("proposal-expired", 410),

    /** One or more request parameters failed schema validation. */
    INVALID_ARGUMENT("invalid-argument", 400),

    /** The request body exceeds the size limit. */
    REQUEST_TOO_LARGE("request-too-large", 413),

    /** Rate limit exceeded for this principal or workspace. */
    RATE_LIMITED("rate-limited", 429),

    /** Budget exhausted for this principal. */
    BUDGET_EXHAUSTED("budget-exhausted", 429),

    /** The backing execution timed out. */
    EXECUTION_TIMEOUT("execution-timeout", 504),

    /** The backing service returned an error. */
    EXECUTION_ERROR("execution-error", 502),

    /** An unexpected internal error occurred. */
    INTERNAL_ERROR("internal-error", 500);

    private static final String BASE_URI = "https://dynamic-ai/problems/";

    private final String code;
    private final int httpStatus;

    ProblemCode(String code, int httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    /** @return the stable code string used in JSON and in the type URI path */
    public String code() {
        return code;
    }

    /** @return the HTTP status code */
    public int httpStatus() {
        return httpStatus;
    }

    /** @return the RFC 9457 type URI for this problem */
    public String typeUri() {
        return BASE_URI + code;
    }
}
