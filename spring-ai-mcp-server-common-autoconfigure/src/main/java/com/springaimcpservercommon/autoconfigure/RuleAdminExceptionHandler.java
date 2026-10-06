package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ruleengine.admin.AdminException;
import com.springaimcpservercommon.webmvc.problem.ProblemCode;
import com.springaimcpservercommon.webmvc.problem.ProblemDetailFactory;
import com.springaimcpservercommon.webmvc.problem.ProblemDetailFactory.FieldViolation;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Turns the rule-engine authoring services' refusals into RFC 9457 problems, scoped to the rule-engine admin
 * controllers only. The stable error code ({@code four_eyes}, {@code parameter_in_use}, {@code confirmation_required} …)
 * is the problem {@code title}; messages name fields and rules, never input values.
 *
 * <ul>
 *   <li>not found (also another workspace's id) → 404</li>
 *   <li>conflict with the current state → 409 (the detail lists what it conflicts with)</li>
 *   <li>invalid content → 400 with one field violation per problem</li>
 * </ul>
 */
@NullMarked
// before AdminExceptionHandler, whose catch-all would otherwise turn a refusal into a 500
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {RuleLifecycleAdminController.class, RuleConfigAdminController.class,
        RuleLibraryAdminController.class})
public final class RuleAdminExceptionHandler {

    /**
     * Maps an authoring refusal.
     *
     * @param e       the refusal
     * @param request current request
     * @return the problem response
     */
    @ExceptionHandler(AdminException.class)
    public ResponseEntity<String> refused(AdminException e, HttpServletRequest request) {
        return switch (e) {
            case AdminException.NotFound n -> AdminApi.problem(ProblemCode.NOT_FOUND, n.code(), n.getMessage(), request);
            case AdminException.Conflict c -> AdminApi.problem(ProblemCode.CONFLICT, c.code(),
                    c.details().isEmpty() ? c.getMessage() : c.getMessage() + " [" + String.join(", ", c.details()) + "]",
                    request);
            case AdminException.Invalid i -> ResponseEntity.status(ProblemCode.INVALID_ARGUMENT.httpStatus())
                    .contentType(AdminApi.PROBLEM_JSON)
                    .body(ProblemDetailFactory.buildValidation(request.getRequestURI(), violations(i)));
        };
    }

    private static List<FieldViolation> violations(AdminException.Invalid i) {
        return i.violations().stream().map(v -> {
            int colon = v.indexOf(": ");
            return colon > 0 ? new FieldViolation(v.substring(0, colon), v.substring(colon + 2))
                    : new FieldViolation(i.code(), v);
        }).toList();
    }
}
