package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.validation.ValidationContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;

/**
 * Derives the {@link ValidationContext} for a controller request body. Declare your own bean to supply the business
 * state (e.g. read it from a path variable or the loaded entity) or a different action naming.
 */
@FunctionalInterface
public interface ValidationContextResolver {

    /**
     * Builds the context.
     *
     * @param request   the current request
     * @param parameter the {@code @RequestBody} parameter being validated
     * @param endpoint  {@code "<METHOD> <matched pattern>"}
     * @return the context
     */
    ValidationContext resolve(HttpServletRequest request, MethodParameter parameter, String endpoint);

    /**
     * Default: stage {@code CONTROLLER}, no state, action = upper-case handler method name.
     *
     * @return the default resolver
     */
    static ValidationContextResolver defaults() {
        return (request, parameter, endpoint) -> ValidationContext.of("CONTROLLER", null, endpoint,
                parameter.getMethod() == null ? null : parameter.getMethod().getName().toUpperCase(java.util.Locale.ROOT));
    }
}
