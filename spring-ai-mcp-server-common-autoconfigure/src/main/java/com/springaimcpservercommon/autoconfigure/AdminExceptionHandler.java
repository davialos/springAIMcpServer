package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.persistence.proposal.ProposalRuleViolationException;
import com.springaimcpservercommon.persistence.support.SqlStates;
import com.springaimcpservercommon.webmvc.problem.ProblemCode;
import com.springaimcpservercommon.webmvc.problem.ProblemDetailFactory;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Turns request-binding and validation failures of the admin cross-cutting controllers into RFC 9457
 * problem responses (LLD-08 §2). Scoped to those controllers only, so it never affects host controllers
 * (LLD-12 §4).
 *
 * <p>Messages are limited to fixed text and parameter names; request bodies and values are never echoed.
 */
@NullMarked
@RestControllerAdvice(assignableTypes = {
        AuditAdminController.class,
        KillSwitchAdminController.class,
        ClusterAdminController.class,
        MeAdminController.class,
        ProposalReviewController.class,
        WorkspaceAdminController.class,
        RoleMappingAdminController.class,
        GrantAdminController.class,
        ServiceAccountAdminController.class})
public final class AdminExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AdminExceptionHandler.class);

    /**
     * Validation failures raised by the controllers and the persistence value types (paging, ranges, ids).
     *
     * @param e       the failure
     * @param request current request
     * @return 400 problem
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badArgument(IllegalArgumentException e, HttpServletRequest request) {
        return AdminApi.problem(ProblemCode.INVALID_ARGUMENT, "Validation failed", e.getMessage(), request);
    }

    /**
     * Unparsable or wrongly typed request body.
     *
     * @param e       the failure
     * @param request current request
     * @return 400 problem
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<String> unreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        return AdminApi.problem(ProblemCode.INVALID_ARGUMENT, "Malformed request body",
                "The request body is missing or is not valid JSON of the expected shape.", request);
    }

    /**
     * A path or query parameter of the wrong type (for example a non-UUID id).
     *
     * @param e       the failure
     * @param request current request
     * @return 400 problem with a field violation
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<String> typeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        return ResponseEntity.status(ProblemCode.INVALID_ARGUMENT.httpStatus())
                .contentType(AdminApi.PROBLEM_JSON)
                .body(ProblemDetailFactory.buildValidation(request.getRequestURI(),
                        List.of(new ProblemDetailFactory.FieldViolation(e.getName(), "has an invalid format"))));
    }

    /**
     * A required query parameter is absent.
     *
     * @param e       the failure
     * @param request current request
     * @return 400 problem with a field violation
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<String> missingParameter(MissingServletRequestParameterException e,
                                                   HttpServletRequest request) {
        return ResponseEntity.status(ProblemCode.INVALID_ARGUMENT.httpStatus())
                .contentType(AdminApi.PROBLEM_JSON)
                .body(ProblemDetailFactory.buildValidation(request.getRequestURI(),
                        List.of(new ProblemDetailFactory.FieldViolation(e.getParameterName(), "is required"))));
    }

    /**
     * Proposal state-machine and ownership rule violations, mapped to stable problem types (LLD-11 §10).
     * Fixed texts only; store messages are never echoed.
     *
     * @param e       the violation
     * @param request current request
     * @return 403, 404, 409, 410 or 412 problem
     */
    @ExceptionHandler(ProposalRuleViolationException.class)
    public ResponseEntity<String> proposalRule(ProposalRuleViolationException e, HttpServletRequest request) {
        return switch (e.reason()) {
            case NOT_OWNER -> AdminApi.problem(ProblemCode.NOT_FOUND, "Proposal not found", null, request);
            case OWNER_CANNOT_APPROVE -> AdminApi.problem(ProblemCode.ACCESS_DENIED, "Access denied",
                    "Segregation of duties: the owner of a proposal cannot approve it.", request);
            case CONTENT_HASH_MISMATCH -> AdminApi.problem(ProblemCode.CONFLICT, "Proposal changed",
                    "The proposal changed since it was reviewed; reload it and review again.", request);
            case EXPIRED -> AdminApi.problem(ProblemCode.PROPOSAL_EXPIRED, "Proposal expired", null, request);
            case STALE_VERSION -> AdminApi.problem(ProblemCode.PRECONDITION_FAILED, "Stale version",
                    "The proposal was changed by someone else; reload it.", request);
            case ILLEGAL_TRANSITION -> AdminApi.problem(ProblemCode.CONFLICT, "Action not allowed in this state",
                    "The proposal is not in a state that allows this action.", request);
            case ALREADY_DECIDED -> AdminApi.problem(ProblemCode.CONFLICT, "Already decided",
                    "This proposal has already been decided by you.", request);
            case IDEMPOTENCY_KEY_REUSED -> AdminApi.problem(ProblemCode.CONFLICT, "Idempotency key reused",
                    "The idempotency key was already used for different content.", request);
        };
    }

    /**
     * Unknown target of an operation.
     *
     * @param e       the failure
     * @param request current request
     * @return 404 problem
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<String> notFound(NoSuchElementException e, HttpServletRequest request) {
        return AdminApi.problem(ProblemCode.NOT_FOUND, "Not found", null, request);
    }

    /**
     * An expected row version (If-Match) no longer matches.
     *
     * @param e       the failure
     * @param request current request
     * @return 412 problem
     */
    @ExceptionHandler(OptimisticLockException.class)
    public ResponseEntity<String> staleVersion(OptimisticLockException e, HttpServletRequest request) {
        return AdminApi.problem(ProblemCode.PRECONDITION_FAILED, "Stale version",
                "The resource was changed by someone else; reload it and retry.", request);
    }

    /**
     * A store refused the change because of the current state of its aggregate.
     *
     * @param e       the failure
     * @param request current request
     * @return 409 problem
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> illegalState(IllegalStateException e, HttpServletRequest request) {
        return AdminApi.problem(ProblemCode.CONFLICT, "Conflict",
                "The request conflicts with the current state of the resource.", request);
    }

    /**
     * Anything else: unique-constraint violations become 409; the rest is logged without request content and
     * answered with a generic problem.
     *
     * @param e       the failure
     * @param request current request
     * @return 409 or 500 problem
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<String> unexpected(RuntimeException e, HttpServletRequest request) {
        if (SqlStates.isUniqueViolation(e)) {
            return AdminApi.problem(ProblemCode.CONFLICT, "Already exists",
                    "A resource with the same unique key already exists.", request);
        }
        if ("23503".equals(SqlStates.sqlState(e))) {
            return AdminApi.problem(ProblemCode.INVALID_ARGUMENT, "Unknown reference",
                    "A referenced id does not exist.", request);
        }
        LOG.error("Unexpected error in admin API {} {}", request.getMethod(), request.getRequestURI(), e);
        return AdminApi.problem(ProblemCode.INTERNAL_ERROR, "Internal error", "An unexpected error occurred.",
                request);
    }
}
