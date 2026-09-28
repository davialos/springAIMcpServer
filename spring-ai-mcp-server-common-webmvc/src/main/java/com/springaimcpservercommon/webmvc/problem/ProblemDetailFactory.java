package com.springaimcpservercommon.webmvc.problem;

import com.springaimcpservercommon.core.json.CanonicalJson;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds RFC 9457 {@code application/problem+json} response bodies (LLD-04 §4).
 *
 * <p>Rules:
 * <ul>
 *   <li>Never includes a stack trace, SQL, prompt content, or internal identifiers.</li>
 *   <li>{@code type} is always a URI from {@link ProblemCode#typeUri()}.</li>
 *   <li>{@code status} matches the HTTP status code.</li>
 *   <li>{@code instance} is the request path (safe public information).</li>
 *   <li>Validation errors include a structured {@code errors[]} extension member.</li>
 * </ul>
 */
@NullMarked
public final class ProblemDetailFactory {

    private ProblemDetailFactory() {}

    /**
     * Builds a simple problem detail.
     *
     * @param code     problem code
     * @param title    short human-readable title
     * @param detail   optional longer human-readable detail; {@code null} to omit
     * @param instance the request URI path or identifier (safe to expose)
     * @return JSON string
     */
    public static String build(ProblemCode code, String title, @Nullable String detail, String instance) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(instance, "instance");
        return CanonicalJson.write(buildMap(code, title, detail, instance, List.of()));
    }

    /**
     * Builds a validation-error problem detail ({@link ProblemCode#INVALID_ARGUMENT})
     * with a structured {@code errors[]} extension member.
     *
     * @param instance     the request URI path
     * @param violations   field → message pairs
     * @return JSON string
     */
    public static String buildValidation(String instance, List<FieldViolation> violations) {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(violations, "violations");
        Map<String, Object> map = buildMap(
                ProblemCode.INVALID_ARGUMENT,
                "Validation failed",
                violations.size() + " parameter(s) failed validation",
                instance,
                violations);
        return CanonicalJson.write(map);
    }

    /**
     * Builds a rate-limited problem detail with a {@code Retry-After} hint.
     *
     * @param instance          the request URI path
     * @param retryAfterSeconds seconds until the caller may retry; 0 = unknown
     * @return JSON string
     */
    public static String buildRateLimited(String instance, int retryAfterSeconds) {
        Map<String, Object> map = buildMap(
                ProblemCode.RATE_LIMITED,
                "Too many requests",
                "Rate limit exceeded. Please slow down.",
                instance,
                List.of());
        if (retryAfterSeconds > 0) map.put("retryAfter", retryAfterSeconds);
        return CanonicalJson.write(map);
    }

    private static Map<String, Object> buildMap(ProblemCode code, String title, @Nullable String detail,
                                                 String instance, List<FieldViolation> violations) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", code.typeUri());
        map.put("title", title);
        map.put("status", code.httpStatus());
        if (detail != null) map.put("detail", detail);
        map.put("instance", instance);
        if (!violations.isEmpty()) {
            List<Map<String, String>> errors = new ArrayList<>(violations.size());
            for (FieldViolation v : violations) {
                Map<String, String> e = new LinkedHashMap<>();
                e.put("field", v.field());
                e.put("message", v.message());
                errors.add(e);
            }
            map.put("errors", errors);
        }
        return map;
    }

    /**
     * A single field validation violation.
     *
     * @param field   parameter name or JSON path
     * @param message human-readable constraint violation message
     */
    public record FieldViolation(String field, String message) {}
}
