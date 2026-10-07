package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.validation.ValidationException;
import com.springaimcpservercommon.validation.Violation;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

/** Maps {@link ValidationException} to 422 {@code application/problem+json} listing the violations (no values). */
@NullMarked
@RestControllerAdvice
class ValidationProblemAdvice {

    @ExceptionHandler(ValidationException.class)
    ResponseEntity<ProblemDetail> onInvalid(ValidationException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                "Request validation failed");
        List<Map<String, Object>> errors = e.result().errors().stream().map(ValidationProblemAdvice::view).toList();
        problem.setProperty("violations", errors);
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    private static Map<String, Object> view(Violation v) {
        return Map.of("rule", v.ruleId(), "field", v.field() == null ? "" : v.field(), "code", v.code(),
                "message", v.message());
    }
}
