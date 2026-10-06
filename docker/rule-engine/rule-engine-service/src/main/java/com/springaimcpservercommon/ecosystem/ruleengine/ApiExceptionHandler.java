package com.springaimcpservercommon.ecosystem.ruleengine;

import com.springaimcpservercommon.ruleengine.UnknownRuleGroupException;
import com.springaimcpservercommon.ruleengine.cache.RuleCatalogUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every failure is an RFC 9457 problem document with a stable {@code code}. Messages never carry fact values, SQL or
 * stack traces; unexpected errors are logged with their class only and answered with a generic 500.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiProblem.class)
    ProblemDetail problem(ApiProblem e) {
        return detail(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(UnknownRuleGroupException.class)
    ProblemDetail unknownGroup(UnknownRuleGroupException e) {
        return detail(HttpStatus.NOT_FOUND, "unknown_rule_group", e.getMessage());
    }

    @ExceptionHandler(RuleCatalogUnavailableException.class)
    ProblemDetail unavailable(RuleCatalogUnavailableException e) {
        log.warn("rule catalog unavailable: {}", e.getClass().getSimpleName());
        return detail(HttpStatus.SERVICE_UNAVAILABLE, "rules_unavailable", "the rule store is not reachable right now");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail malformed(IllegalArgumentException e) {
        return detail(HttpStatus.BAD_REQUEST, "invalid_request", "the request could not be read");
    }

    /**
     * Spring MVC's own failures (unreadable body, bad parameter, unknown path, wrong method, unsupported media type) keep
     * the status the framework chose; they only gain the stable {@code code} and lose the framework's wording, which can
     * quote request content.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        ProblemDetail p = ProblemDetail.forStatus(status);
        p.setTitle(HttpStatus.valueOf(status.value()).getReasonPhrase());
        p.setDetail(switch (status.value()) {
            case 404 -> "no such resource";
            case 405 -> "method not allowed";
            case 406, 415 -> "unsupported media type";
            default -> "the request could not be read";
        });
        p.setProperty("code", switch (status.value()) {
            case 404 -> "not_found";
            case 405 -> "method_not_allowed";
            case 406, 415 -> "unsupported_media_type";
            default -> "invalid_request";
        });
        return super.handleExceptionInternal(ex, p, headers, status, request);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ProblemDetail duplicate(DuplicateKeyException e) {
        return detail(HttpStatus.CONFLICT, "duplicate", "an item with the same code already exists in this scope");
    }

    @ExceptionHandler(DataAccessException.class)
    ProblemDetail data(DataAccessException e) {
        log.error("database error: {}", e.getClass().getSimpleName());
        return detail(HttpStatus.SERVICE_UNAVAILABLE, "database_error", "the database could not complete the request");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("unexpected error: {}", e.getClass().getName());
        return detail(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "unexpected error");
    }

    static ProblemDetail detail(HttpStatus status, String code, String message) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, message);
        p.setTitle(status.getReasonPhrase());
        p.setProperty("code", code);
        return p;
    }
}
