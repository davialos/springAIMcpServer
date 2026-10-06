package com.springaimcpservercommon.loadtest.runtime;

import org.jspecify.annotations.Nullable;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Describes Spring MVC's registered handler methods as an OpenAPI 3 document: path, method, parameters
 * ({@code @PathVariable}, {@code @RequestParam}, {@code @RequestHeader}, request-body and multipart parts, query
 * objects), the success response and, as extensions, the handler ({@code x-loadtest-handler}) and the method-security
 * access ({@code x-loadtest-access}). What the generator could only guess from source it reads here as the framework
 * resolved it: composed annotations, inherited mappings, routes registered at runtime.
 */
final class RuntimeModelBuilder {

    private static final ParameterNameDiscoverer NAMES = new DefaultParameterNameDiscoverer();
    private static final Set<String> SKIPPED_HEADERS = Set.of("authorization", "accept", "content-type", "host", "cookie");
    private static final Set<String> NO_BODY = Set.of("GET", "HEAD", "DELETE", "OPTIONS");

    private final LoadTestRuntimeProperties properties;
    private final TypeSchemas requests = new TypeSchemas(TypeSchemas.Direction.REQUEST);
    private final TypeSchemas responses = new TypeSchemas(TypeSchemas.Direction.RESPONSE);

    RuntimeModelBuilder(LoadTestRuntimeProperties properties) {
        this.properties = properties;
    }

    Map<String, Object> build(String title, String contextPath, List<RequestMappingHandlerMapping> mappings) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("openapi", "3.0.3");
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", title);
        info.put("version", "runtime");
        doc.put("info", info);
        if (!contextPath.isEmpty() && !contextPath.equals("/")) {
            doc.put("servers", List.of(Map.of("url", contextPath)));
        }
        Map<String, Map<String, Object>> paths = new LinkedHashMap<>();
        Set<String> operationIds = new LinkedHashSet<>();
        int routes = 0;
        for (RequestMappingHandlerMapping mapping : mappings) {
            for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
                HandlerMethod handler = e.getValue();
                if (excluded(handler)) {
                    continue;
                }
                RequestMappingInfo info0 = e.getKey();
                Set<String> patterns = patterns(info0);
                Set<String> methods = new LinkedHashSet<>();
                info0.getMethodsCondition().getMethods().forEach(m -> methods.add(m.name()));
                if (methods.isEmpty()) {
                    methods.add("GET"); // a mapping without a method answers every method; GET is the safe one to test
                }
                for (String pattern : patterns) {
                    String path = normalise(pattern);
                    if (path == null || properties.excludePaths().stream().anyMatch(path::startsWith)) {
                        continue;
                    }
                    for (String method : methods) {
                        Map<String, Object> operation = operation(handler, info0, method, path, operationIds);
                        paths.computeIfAbsent(path, p -> new LinkedHashMap<>()).put(method.toLowerCase(java.util.Locale.ROOT), operation);
                        routes++;
                    }
                }
            }
        }
        doc.put("paths", paths);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("generatedBy", "spring-ai-mcp-server-common-loadtest-runtime");
        meta.put("routes", routes);
        doc.put("x-loadtest", meta);
        return doc;
    }

    private boolean excluded(HandlerMethod handler) {
        String owner = handler.getBeanType().getName();
        return properties.excludePackages().stream().anyMatch(owner::startsWith);
    }

    private static Set<String> patterns(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null) {
            return info.getPathPatternsCondition().getPatternValues();
        }
        return info.getPatternsCondition() == null ? Set.of() : info.getPatternsCondition().getPatterns();
    }

    /** {@code /orders/{id:\d+}} → {@code /orders/{id}}; a wildcard route cannot be called as it is: left out. */
    private static @Nullable String normalise(String pattern) {
        if (pattern.contains("*")) {
            return null;
        }
        String path = pattern.replaceAll("\\{([^}:/]+):[^}]*}", "{$1}");
        return path.isEmpty() ? "/" : path;
    }

    private Map<String, Object> operation(HandlerMethod handler, RequestMappingInfo info, String method, String path,
                                          Set<String> operationIds) {
        Map<String, Object> op = new LinkedHashMap<>();
        String base = handler.getMethod().getName();
        String id = operationIds.add(base) ? base : unique(base, method, operationIds);
        op.put("operationId", id);
        op.put("tags", List.of(handler.getBeanType().getSimpleName()));
        List<Map<String, Object>> parameters = new ArrayList<>();
        Map<String, Object> body = null;
        Map<String, Object> multipart = null;
        for (MethodParameter p : handler.getMethodParameters()) {
            p.initParameterNameDiscovery(NAMES);
            ResolvableType type = ResolvableType.forMethodParameter(p);
            PathVariable pv = p.getParameterAnnotation(PathVariable.class);
            RequestParam rp = p.getParameterAnnotation(RequestParam.class);
            RequestHeader rh = p.getParameterAnnotation(RequestHeader.class);
            RequestPart part = p.getParameterAnnotation(RequestPart.class);
            if (p.hasParameterAnnotation(RequestBody.class)) {
                body = requests.of(type);
            } else if (pv != null) {
                parameters.add(parameter(name(pv.name(), pv.value(), p), "path", true, requests.of(type), null));
            } else if (part != null) {
                multipart = multipart == null ? new LinkedHashMap<>() : multipart;
                multipart.put(name(part.name(), part.value(), p), requests.of(type));
            } else if (rh != null) {
                String name = name(rh.name(), rh.value(), p);
                if (!SKIPPED_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                    parameters.add(parameter(name, "header", rh.required() && defaultValue(rh.defaultValue()) == null,
                            requests.of(type), defaultValue(rh.defaultValue())));
                }
            } else if (rp != null) {
                String name = name(rp.name(), rp.value(), p);
                Map<String, Object> schema = requests.of(type);
                if (isFile(schema)) {
                    multipart = multipart == null ? new LinkedHashMap<>() : multipart;
                    multipart.put(name, schema);
                } else if (!"object".equals(schema.get("type")) || schema.containsKey("properties")) {
                    parameters.add(parameter(name, "query", rp.required() && defaultValue(rp.defaultValue()) == null, schema,
                            defaultValue(rp.defaultValue())));
                }
            } else if (isQueryObject(type)) {
                queryObject(parameters, requests.of(type));
            } else if (isSimple(type) && p.getParameterName() != null) {
                parameters.add(parameter(p.getParameterName(), "query", false, requests.of(type), null));
            } else if (type.resolve(Object.class).getName().equals("org.springframework.data.domain.Pageable")) {
                parameters.add(parameter("page", "query", false, Map.of("type", "integer", "minimum", 0), null));
                parameters.add(parameter("size", "query", false, Map.of("type", "integer", "minimum", 1, "maximum", 100), null));
            }
        }
        if (!parameters.isEmpty()) {
            op.put("parameters", parameters);
        }
        if (multipart != null) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", multipart);
            op.put("requestBody", Map.of("content", Map.of("multipart/form-data", Map.of("schema", schema))));
        } else if (body != null && !NO_BODY.contains(method)) {
            op.put("requestBody", Map.of("required", true, "content", Map.of(requestType(info), Map.of("schema", body))));
        }
        op.put("responses", responses(handler, info));
        op.put("x-loadtest-handler", handler.getBeanType().getName() + "#" + handler.getMethod().getName());
        Map<String, Object> access = RuntimeAccess.of(handler);
        if (access != null) {
            op.put("x-loadtest-access", access);
        }
        return op;
    }

    private static String unique(String base, String method, Set<String> ids) {
        String id = base + method.charAt(0) + method.substring(1).toLowerCase(java.util.Locale.ROOT);
        for (int i = 2; !ids.add(id); i++) {
            id = base + i;
        }
        return id;
    }

    private static String requestType(RequestMappingInfo info) {
        for (MediaType t : info.getConsumesCondition().getConsumableMediaTypes()) {
            if (t.getSubtype().contains("json") || t.equals(MediaType.APPLICATION_FORM_URLENCODED)) {
                return t.toString();
            }
        }
        return MediaType.APPLICATION_JSON_VALUE;
    }

    private Map<String, Object> responses(HandlerMethod handler, RequestMappingInfo info) {
        Map<String, Object> out = new LinkedHashMap<>();
        ResponseStatus status = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), ResponseStatus.class);
        HttpStatus code = status == null ? HttpStatus.OK : status.code() != HttpStatus.INTERNAL_SERVER_ERROR ? status.code() : status.value();
        ResolvableType returned = ResolvableType.forMethodReturnType(handler.getMethod(), handler.getBeanType());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("description", code.getReasonPhrase());
        Map<String, Object> schema = responses.of(returned);
        boolean text = info.getProducesCondition().getProducibleMediaTypes().stream().anyMatch(t -> t.getType().equals("text"));
        if (!schema.isEmpty() && !text && code != HttpStatus.NO_CONTENT) {
            response.put("content", Map.of(MediaType.APPLICATION_JSON_VALUE, Map.of("schema", schema)));
        }
        out.put(String.valueOf(code.value()), response);
        return out;
    }

    private static Map<String, Object> parameter(String name, String in, boolean required, Map<String, Object> schema,
                                                 @Nullable String defaultValue) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("in", in);
        p.put("required", required);
        Map<String, Object> s = new LinkedHashMap<>(schema);
        if (defaultValue != null) {
            s.put("default", defaultValue);
        }
        p.put("schema", s);
        return p;
    }

    private static @Nullable String defaultValue(String value) {
        return ValueConstants.DEFAULT_NONE.equals(value) ? null : value;
    }

    private static String name(String name, String value, MethodParameter p) {
        if (!name.isEmpty()) {
            return name;
        }
        if (!value.isEmpty()) {
            return value;
        }
        String discovered = p.getParameterName();
        return discovered == null ? "arg" + p.getParameterIndex() : discovered;
    }

    private static boolean isFile(Map<String, Object> schema) {
        return "binary".equals(schema.get("format")) || "array".equals(schema.get("type"))
                && schema.get("items") instanceof Map<?, ?> items && "binary".equals(items.get("format"));
    }

    private static boolean isSimple(ResolvableType type) {
        Class<?> raw = TypeSchemas.unwrap(type).resolve(Object.class);
        return raw.isPrimitive() || raw == String.class || Number.class.isAssignableFrom(raw) || raw == Boolean.class
                || raw.isEnum() || raw == java.util.UUID.class || raw == java.time.LocalDate.class;
    }

    /** A plain bean argument (not a Spring/servlet/JDK type): Spring binds its properties from the query string. */
    private static boolean isQueryObject(ResolvableType type) {
        Class<?> raw = type.resolve(Object.class);
        String n = raw.getName();
        return !raw.isPrimitive() && !raw.isArray() && !raw.isInterface() && !raw.isEnum() && !n.startsWith("java.")
                && !n.startsWith("jakarta.") && !n.startsWith("org.springframework.") && !n.startsWith("tools.jackson.")
                && !n.startsWith("com.fasterxml.");
    }

    @SuppressWarnings("unchecked")
    private static void queryObject(List<Map<String, Object>> parameters, Map<String, Object> schema) {
        if (!(schema.get("properties") instanceof Map<?, ?> props)) {
            return;
        }
        List<String> required = schema.get("required") instanceof List<?> r ? (List<String>) r : List.of();
        for (Map.Entry<?, ?> e : props.entrySet()) {
            Map<String, Object> s = (Map<String, Object>) e.getValue();
            if (!"object".equals(s.get("type"))) { // nested objects need dotted names: not expanded
                parameters.add(parameter(String.valueOf(e.getKey()), "query", required.contains(String.valueOf(e.getKey())), s, null));
            }
        }
    }
}
