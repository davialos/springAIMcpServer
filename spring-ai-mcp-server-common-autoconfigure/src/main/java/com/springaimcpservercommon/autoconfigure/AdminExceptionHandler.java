package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.webmvc.problem.ProblemCode;
import com.springaimcpservercommon.webmvc.problem.ProblemDetailFactory;
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
        MeAdminController.class})
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
     * Anything else: logged without request content, answered with a generic problem.
     *
     * @param e       the failure
     * @param request current request
     * @return 500 problem
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<String> unexpected(RuntimeException e, HttpServletRequest request) {
        LOG.error("Unexpected error in admin API {} {}", request.getMethod(), request.getRequestURI(), e);
        return AdminApi.problem(ProblemCode.INTERNAL_ERROR, "Internal error", "An unexpected error occurred.",
                request);
    }
}
