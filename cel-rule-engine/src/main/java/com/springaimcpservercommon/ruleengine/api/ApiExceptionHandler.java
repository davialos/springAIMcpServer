package com.springaimcpservercommon.ruleengine.api;

import com.springaimcpservercommon.ruleengine.channel.ConfirmationRequiredException;
import com.springaimcpservercommon.ruleengine.library.CelEngine;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/** Errors as RFC 9457 problem details with a machine-readable {@code code}. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(RuleEngineException.class)
    ProblemDetail ruleEngine(RuleEngineException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        p.setProperty("code", e.code());
        return p;
    }

    /** The UI shows the message in a pop-up and repeats the request with {@code confirmExternal: true}. */
    @ExceptionHandler(ConfirmationRequiredException.class)
    ProblemDetail confirmation(ConfirmationRequiredException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        p.setProperty("code", e.code());
        p.setProperty("confirmationRequired", true);
        return p;
    }

    @ExceptionHandler(CelEngine.ExpressionException.class)
    ProblemDetail expression(CelEngine.ExpressionException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                "the expression is not valid");
        p.setProperty("code", "INVALID_EXPRESSION");
        p.setProperty("problems", e.problems());
        return p;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "the request is not valid");
        p.setProperty("code", "VALIDATION_FAILED");
        List<String> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage()).toList();
        p.setProperty("problems", errors);
        return p;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "the change conflicts with existing data (a duplicate code, or something still in use)");
        p.setProperty("code", "DATA_CONFLICT");
        return p;
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ProblemDetail illegal(RuntimeException e) {
        HttpStatus status = e instanceof IllegalStateException ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_REQUEST;
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        p.setProperty("code", status == HttpStatus.BAD_REQUEST ? "BAD_REQUEST" : "NOT_ALLOWED");
        return p;
    }
}
