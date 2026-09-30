package com.springaimcpservercommon.autoconfigure;

import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;

/**
 * Maps the library's controllers into the host's {@link RequestMappingHandlerMapping}.
 *
 * <p>The library's controllers carry a type-level {@link RequestMapping} but no {@link Controller} stereotype: library
 * classes are never components (they are declared as {@code @Bean}s in the auto-configurations, so a host that
 * component-scans our package cannot register them twice). Spring MVC 7 only detects {@code @Controller} types, so
 * this registrar registers their handler methods with the public {@code registerMapping}, once all singletons exist.
 * Using the host's mapping (not a second one) keeps the host's path matching, CORS configuration and interceptors.
 *
 * <p>Only beans whose class is in {@code com.springaimcpservercommon} and is not already a {@code @Controller} are
 * touched, so host controllers are never registered twice.
 */
@NullMarked
final class DaiControllerRegistrar implements SmartInitializingSingleton {

    static final String LIBRARY_PACKAGE = "com.springaimcpservercommon.";

    private static final Logger LOG = LoggerFactory.getLogger(DaiControllerRegistrar.class);

    private final ObjectProvider<RequestMappingHandlerMapping> mapping;
    private final ListableBeanFactory beans;

    DaiControllerRegistrar(ObjectProvider<RequestMappingHandlerMapping> mapping, ListableBeanFactory beans) {
        this.mapping = Objects.requireNonNull(mapping, "mapping");
        this.beans = Objects.requireNonNull(beans, "beans");
    }

    @Override
    public void afterSingletonsInstantiated() {
        RequestMappingHandlerMapping handlerMapping = hostMapping();
        if (handlerMapping == null) {
            LOG.warn("No RequestMappingHandlerMapping in the context; the dynamic-ai HTTP APIs are not mapped");
            return;
        }
        int registered = 0;
        for (Map.Entry<String, Object> entry : beans.getBeansWithAnnotation(RequestMapping.class).entrySet()) {
            Class<?> type = ClassUtils.getUserClass(entry.getValue());
            if (!type.getName().startsWith(LIBRARY_PACKAGE)
                    || AnnotatedElementUtils.hasAnnotation(type, Controller.class)) {
                continue;
            }
            registered += register(handlerMapping, entry.getValue(), type);
        }
        LOG.debug("Mapped {} dynamic-ai handler method(s)", registered);
    }

    /**
     * The host's main mapping: the primary or only one, else the bean Spring MVC itself names
     * {@code requestMappingHandlerMapping} (Actuator adds a second {@code RequestMappingHandlerMapping} for its own
     * endpoints, which must not receive ours).
     */
    private @org.jspecify.annotations.Nullable RequestMappingHandlerMapping hostMapping() {
        RequestMappingHandlerMapping unique = mapping.getIfUnique();
        if (unique != null) {
            return unique;
        }
        return beans.containsBean("requestMappingHandlerMapping")
                ? beans.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class) : null;
    }

    private static int register(RequestMappingHandlerMapping handlerMapping, Object handler, Class<?> type) {
        RequestMappingInfo.BuilderConfiguration options = handlerMapping.getBuilderConfiguration();
        RequestMapping typeMapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
        RequestMappingInfo typeInfo = typeMapping == null ? null : info(typeMapping, options);
        Map<Method, RequestMapping> methods = MethodIntrospector.selectMethods(type,
                (MethodIntrospector.MetadataLookup<RequestMapping>) m ->
                        AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class));
        methods.forEach((method, methodMapping) -> {
            RequestMappingInfo methodInfo = info(methodMapping, options);
            RequestMappingInfo combined = typeInfo == null ? methodInfo : typeInfo.combine(methodInfo);
            handlerMapping.registerMapping(combined, handler, method);
        });
        return methods.size();
    }

    private static RequestMappingInfo info(RequestMapping m, RequestMappingInfo.BuilderConfiguration options) {
        return RequestMappingInfo.paths(m.path())
                .methods(m.method())
                .params(m.params())
                .headers(m.headers())
                .consumes(m.consumes())
                .produces(m.produces())
                .mappingName(m.name().isEmpty() ? null : m.name())
                .options(options)
                .build();
    }
}
