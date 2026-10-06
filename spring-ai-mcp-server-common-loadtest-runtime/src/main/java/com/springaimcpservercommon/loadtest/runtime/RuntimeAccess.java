package com.springaimcpservercommon.loadtest.runtime;

import org.jspecify.annotations.Nullable;
import org.springframework.web.method.HandlerMethod;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Who may call a handler method, from method security: {@code @PreAuthorize} (the roles and authorities of
 * {@code hasRole}/{@code hasAnyRole}/{@code hasAuthority}/{@code hasAnyAuthority}; {@code permitAll}, {@code denyAll},
 * {@code isAuthenticated()}), {@code @Secured}, {@code @RolesAllowed}, {@code @PermitAll}, {@code @DenyAll} — read by
 * annotation name, so Spring Security need not be on this module's classpath. The method wins over its class. URL
 * rules of the {@code SecurityFilterChain} are not visible here: the generator reads them from the project sources.
 */
final class RuntimeAccess {

    private static final Pattern ROLE_CALL = Pattern.compile("(hasRole|hasAnyRole|hasAuthority|hasAnyAuthority)\\s*\\(([^()]*)\\)");
    private static final Pattern QUOTED = Pattern.compile("'([^']*)'|\"([^\"]*)\"");

    private RuntimeAccess() {
    }

    /**
     * The access of a handler method.
     *
     * @param handler the handler
     * @return {@code {kind, roles}} for the {@code x-loadtest-access} extension, or {@code null} when nothing says
     */
    static @Nullable Map<String, Object> of(HandlerMethod handler) {
        Map<String, Object> onMethod = from(handler.getMethod());
        return onMethod != null ? onMethod : from(handler.getBeanType());
    }

    private static @Nullable Map<String, Object> from(AnnotatedElement element) {
        for (Annotation a : element.getAnnotations()) {
            String type = a.annotationType().getName();
            Object value = TypeSchemas.call(a, "value");
            switch (type) {
                case "org.springframework.security.access.prepost.PreAuthorize" -> {
                    Map<String, Object> m = expression(String.valueOf(value));
                    if (m != null) {
                        return m;
                    }
                }
                case "org.springframework.security.access.annotation.Secured", "jakarta.annotation.security.RolesAllowed" -> {
                    List<String> roles = new ArrayList<>();
                    if (value instanceof String[] names) {
                        for (String n : names) {
                            roles.add(n.startsWith("ROLE_") ? n.substring(5) : n);
                        }
                    }
                    return access("ROLES", roles);
                }
                case "jakarta.annotation.security.PermitAll" -> {
                    return access("PUBLIC", List.of());
                }
                case "jakarta.annotation.security.DenyAll" -> {
                    return access("DENIED", List.of());
                }
                default -> { }
            }
        }
        return null;
    }

    /** A {@code @PreAuthorize} expression; combined conditions keep their roles (the argument checks are runtime data). */
    static @Nullable Map<String, Object> expression(String expression) {
        String e = expression.strip();
        if (e.equals("permitAll()") || e.equals("true")) {
            return access("PUBLIC", List.of());
        }
        if (e.equals("denyAll()") || e.equals("false")) {
            return access("DENIED", List.of());
        }
        Set<String> roles = new LinkedHashSet<>();
        Matcher call = ROLE_CALL.matcher(e);
        while (call.find()) {
            Matcher q = QUOTED.matcher(call.group(2));
            while (q.find()) {
                String r = q.group(1) != null ? q.group(1) : q.group(2);
                roles.add(r.startsWith("ROLE_") ? r.substring(5) : r);
            }
        }
        if (!roles.isEmpty()) {
            return access("ROLES", new ArrayList<>(roles));
        }
        if (e.contains("isAuthenticated()") || e.contains("isFullyAuthenticated()") || e.contains("authenticated")) {
            return access("AUTHENTICATED", List.of());
        }
        return null;
    }

    private static Map<String, Object> access(String kind, List<String> roles) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("roles", roles);
        return m;
    }
}
