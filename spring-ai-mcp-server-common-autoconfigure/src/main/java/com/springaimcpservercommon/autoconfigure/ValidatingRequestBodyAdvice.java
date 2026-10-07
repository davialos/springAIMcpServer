package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.validation.Validator;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.lang.reflect.Type;

/**
 * Runs the {@link Validator} on every {@code @RequestBody} the host's controllers receive (opt-in, ADR-0027). The
 * library's own {@code /dynamic-ai/**} routes are skipped. Failures surface as a
 * {@link com.springaimcpservercommon.validation.ValidationException}, mapped by {@link ValidationProblemAdvice}.
 */
@NullMarked
class ValidatingRequestBodyAdvice extends RequestBodyAdviceAdapter {

    private static final String OWN_PREFIX = "/dynamic-ai/";

    private final Validator validator;
    private final ValidationContextResolver resolver;

    ValidatingRequestBodyAdvice(Validator validator, ValidationContextResolver resolver) {
        this.validator = validator;
        this.resolver = resolver;
    }

    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
                                Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return body;
        }
        HttpServletRequest request = attrs.getRequest();
        if (request.getRequestURI().startsWith(OWN_PREFIX)) {
            return body;
        }
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String endpoint = request.getMethod() + " " + (pattern != null ? pattern : request.getRequestURI());
        validator.validateOrThrow(body, resolver.resolve(request, parameter, endpoint));
        return body;
    }
}
