package com.springaimcpservercommon.loadtest.runtime;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.util.Map;

/**
 * Bean Validation constraints ({@code jakarta.validation.constraints.*}, Hibernate Validator's {@code @Length} and
 * {@code @Range}) written into a property's schema as OpenAPI keywords. Read by annotation name and attribute, so
 * the host needs no validation library for this module and a host without one simply has no constraints.
 */
final class Constraints {

    private Constraints() {
    }

    /**
     * Adds the constraints found on a property to its schema.
     *
     * @param schema  the property's schema
     * @param sources the field, getter or record component
     * @return {@code true} when a constraint makes the property required ({@code @NotNull}, {@code @NotBlank}, {@code @NotEmpty})
     */
    static boolean apply(Map<String, Object> schema, AnnotatedElement[] sources) {
        boolean required = false;
        boolean array = "array".equals(schema.get("type"));
        for (AnnotatedElement source : sources) {
            if (source == null) {
                continue;
            }
            for (Annotation a : source.getAnnotations()) {
                String type = a.annotationType().getName();
                if (!type.startsWith("jakarta.validation.constraints.") && !type.startsWith("org.hibernate.validator.constraints.")) {
                    continue;
                }
                switch (a.annotationType().getSimpleName()) {
                    case "NotNull" -> required = true;
                    case "NotBlank" -> {
                        required = true;
                        put(schema, "minLength", 1, null);
                    }
                    case "NotEmpty" -> {
                        required = true;
                        put(schema, array ? "minItems" : "minLength", 1, null);
                    }
                    case "Size", "Length" -> {
                        put(schema, array ? "minItems" : "minLength", TypeSchemas.call(a, "min"), 0);
                        put(schema, array ? "maxItems" : "maxLength", TypeSchemas.call(a, "max"), Integer.MAX_VALUE);
                    }
                    case "Min" -> put(schema, "minimum", TypeSchemas.call(a, "value"), null);
                    case "Max" -> put(schema, "maximum", TypeSchemas.call(a, "value"), null);
                    case "Range" -> {
                        put(schema, "minimum", TypeSchemas.call(a, "min"), null);
                        put(schema, "maximum", TypeSchemas.call(a, "max"), null);
                    }
                    case "DecimalMin" -> put(schema, "minimum", number(TypeSchemas.call(a, "value")), null);
                    case "DecimalMax" -> put(schema, "maximum", number(TypeSchemas.call(a, "value")), null);
                    case "Positive" -> schema.putIfAbsent("minimum", "integer".equals(schema.get("type")) ? 1 : 0.0001);
                    case "PositiveOrZero" -> schema.putIfAbsent("minimum", 0);
                    case "Negative" -> schema.putIfAbsent("maximum", "integer".equals(schema.get("type")) ? -1 : -0.0001);
                    case "NegativeOrZero" -> schema.putIfAbsent("maximum", 0);
                    case "Pattern" -> {
                        Object regexp = TypeSchemas.call(a, "regexp");
                        if (regexp instanceof String s && !s.isEmpty()) {
                            schema.putIfAbsent("pattern", s);
                        }
                    }
                    case "Email" -> schema.putIfAbsent("format", "email");
                    default -> { }
                }
            }
        }
        return required;
    }

    /** Sets a bound; when several constraints bound the same keyword the stricter one wins, whatever the annotation order. */
    private static void put(Map<String, Object> schema, String key, Object value, Object ignoredDefault) {
        if (!(value instanceof Number n) || ignoredDefault != null && n.equals(ignoredDefault)) {
            return;
        }
        boolean lower = key.startsWith("min");
        Object existing = schema.get(key);
        if (!(existing instanceof Number e) || (lower ? n.doubleValue() > e.doubleValue() : n.doubleValue() < e.doubleValue())) {
            schema.put(key, n);
        }
    }

    private static Object number(Object text) {
        try {
            return text instanceof String s ? Double.valueOf(s) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
