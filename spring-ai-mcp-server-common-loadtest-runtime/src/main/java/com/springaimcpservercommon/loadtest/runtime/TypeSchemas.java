package com.springaimcpservercommon.loadtest.runtime;

import org.jspecify.annotations.Nullable;
import org.springframework.core.ResolvableType;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Java types as OpenAPI schemas, the way Jackson will (de)serialise them: records and beans by their components /
 * getters / fields, {@code @JsonProperty} renames and {@code @JsonIgnore} honoured, enums by constant name, wrappers
 * ({@code Optional}, {@code ResponseEntity}, futures, {@code Mono}) looked through. A request shape also carries the
 * Bean Validation constraints ({@code @NotNull}, {@code @Size}, {@code @Min}, {@code @Pattern} …, read by annotation
 * name so no validation library is needed here). Nesting is cut at {@link #MAX_DEPTH} and at cycles ({@code {}}).
 */
final class TypeSchemas {

    static final int MAX_DEPTH = 6;
    private static final Set<String> WRAPPERS = Set.of("java.util.Optional", "org.springframework.http.ResponseEntity",
            "org.springframework.http.HttpEntity", "java.util.concurrent.Callable", "java.util.concurrent.CompletableFuture",
            "java.util.concurrent.Future", "org.springframework.web.context.request.async.DeferredResult",
            "org.springframework.web.context.request.async.WebAsyncTask", "reactor.core.publisher.Mono",
            "org.springframework.core.io.support.ResourceRegion");
    private static final Set<String> STREAMS = Set.of("reactor.core.publisher.Flux", "java.util.stream.Stream");

    /** Whether a shape is read from a request (constraints, required by validation) or written as a response. */
    enum Direction { REQUEST, RESPONSE }

    private final Direction direction;

    TypeSchemas(Direction direction) {
        this.direction = direction;
    }

    /**
     * The schema of a type.
     *
     * @param type the Java type
     * @return the schema, {@code {}} when it cannot be described
     */
    Map<String, Object> of(ResolvableType type) {
        return of(type, 0, new LinkedHashSet<>());
    }

    /**
     * The type inside wrappers ({@code ResponseEntity<Optional<Order>>} → {@code Order}); {@code null} for a stream
     * element wrapper ({@code Flux<T>} is reported as an array of {@code T}), and the raw class for {@code void}.
     */
    static ResolvableType unwrap(ResolvableType type) {
        ResolvableType t = type;
        for (int i = 0; i < 4; i++) {
            Class<?> raw = t.resolve(Object.class);
            if (WRAPPERS.contains(raw.getName()) && t.hasGenerics()) {
                t = t.getGeneric(0);
            } else {
                break;
            }
        }
        return t;
    }

    private Map<String, Object> of(ResolvableType declared, int depth, Set<Class<?>> path) {
        ResolvableType type = unwrap(declared);
        Class<?> raw = type.resolve(Object.class);
        Map<String, Object> s = new LinkedHashMap<>();
        if (raw == void.class || raw == Void.class) {
            return s;
        }
        if (STREAMS.contains(raw.getName())) {
            return array(type.hasGenerics() ? type.getGeneric(0) : ResolvableType.NONE, depth, path);
        }
        String scalar = scalarType(raw);
        if (scalar != null) {
            return scalarSchema(raw, scalar);
        }
        if (raw.isEnum()) {
            s.put("type", "string");
            List<String> names = new ArrayList<>();
            for (Object c : raw.getEnumConstants()) {
                names.add(((Enum<?>) c).name());
            }
            s.put("enum", names);
            return s;
        }
        if (raw.isArray()) {
            return array(ResolvableType.forClass(raw.getComponentType()), depth, path);
        }
        if (Collection.class.isAssignableFrom(raw)) {
            return array(type.as(Collection.class).getGeneric(0), depth, path);
        }
        if (Map.class.isAssignableFrom(raw)) {
            s.put("type", "object");
            return s;
        }
        if (depth >= MAX_DEPTH || path.contains(raw) || raw.getName().startsWith("java.") || raw.getName().startsWith("jakarta.")
                || raw.isInterface() || raw == Object.class || raw.getName().startsWith("tools.jackson.")
                || raw.getName().startsWith("com.fasterxml.")) {
            return s;
        }
        path.add(raw);
        try {
            return bean(type, raw, depth, path);
        } finally {
            path.remove(raw);
        }
    }

    private Map<String, Object> array(ResolvableType element, int depth, Set<Class<?>> path) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "array");
        s.put("items", element == ResolvableType.NONE ? new LinkedHashMap<String, Object>() : of(element, depth + 1, path));
        return s;
    }

    private static @Nullable String scalarType(Class<?> raw) {
        if (raw == String.class || CharSequence.class.isAssignableFrom(raw) || raw == char.class || raw == Character.class
                || raw == UUID.class || raw == java.net.URI.class || raw == java.net.URL.class || raw == java.util.Locale.class
                || raw == java.util.Date.class || Temporal.class.isAssignableFrom(raw) || raw == java.time.Duration.class
                || raw == java.time.Period.class || raw == java.time.ZoneId.class || raw == byte[].class
                || raw.getName().equals("org.springframework.web.multipart.MultipartFile")
                || raw.getName().equals("org.springframework.core.io.Resource")
                || raw.getName().equals("org.springframework.core.io.InputStreamResource")) {
            return "string";
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return "boolean";
        }
        if (raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class || raw == short.class
                || raw == Short.class || raw == byte.class || raw == Byte.class || raw == BigInteger.class
                || raw == java.util.concurrent.atomic.AtomicInteger.class || raw == java.util.concurrent.atomic.AtomicLong.class) {
            return "integer";
        }
        if (raw == float.class || raw == Float.class || raw == double.class || raw == Double.class || raw == BigDecimal.class
                || Number.class.isAssignableFrom(raw)) {
            return "number";
        }
        return null;
    }

    private static Map<String, Object> scalarSchema(Class<?> raw, String type) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", type);
        if (raw == UUID.class) {
            s.put("format", "uuid");
        } else if (raw == java.time.LocalDate.class) {
            s.put("format", "date");
        } else if (raw == java.time.LocalTime.class || raw == java.time.OffsetTime.class) {
            s.put("format", "time");
        } else if (raw == java.util.Date.class || raw == java.time.Instant.class || raw == java.time.LocalDateTime.class
                || raw == java.time.OffsetDateTime.class || raw == java.time.ZonedDateTime.class) {
            s.put("format", "date-time");
        } else if (raw == int.class || raw == Integer.class) {
            s.put("format", "int32");
        } else if (raw == long.class || raw == Long.class) {
            s.put("format", "int64");
        } else if (raw == byte[].class || raw.getName().startsWith("org.springframework.web.multipart.")
                || raw.getName().startsWith("org.springframework.core.io.")) {
            s.put("format", raw == byte[].class ? "byte" : "binary");
        }
        return s;
    }

    // ── beans and records ───────────────────────────────────────────────────────────────────────────────

    private record Property(String name, ResolvableType type, AnnotatedElement[] sources, boolean primitive) {
    }

    private Map<String, Object> bean(ResolvableType type, Class<?> raw, int depth, Set<Class<?>> path) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Property p : properties(type, raw)) {
            Map<String, Object> schema = of(p.type(), depth + 1, path);
            boolean mustHave = p.primitive() && direction == Direction.RESPONSE; // a primitive is always serialised
            if (direction == Direction.REQUEST) {
                mustHave = Constraints.apply(schema, p.sources());
            }
            if (jsonRequired(p.sources())) {
                mustHave = true;
            }
            properties.put(p.name(), schema);
            if (mustHave) {
                required.add(p.name());
            }
        }
        if (!properties.isEmpty()) {
            s.put("properties", properties);
        }
        if (!required.isEmpty()) {
            s.put("required", required);
        }
        return s;
    }

    private List<Property> properties(ResolvableType type, Class<?> raw) {
        Set<String> ignored = new LinkedHashSet<>(ignoredNames(raw));
        Map<String, Property> found = new LinkedHashMap<>();
        if (raw.isRecord()) {
            for (RecordComponent c : raw.getRecordComponents()) {
                AnnotatedElement[] sources = {c, c.getAccessor(), field(raw, c.getName())};
                add(found, ignored, c.getName(), ResolvableType.forType(c.getGenericType(), type), sources, c.getType().isPrimitive());
            }
            return new ArrayList<>(found.values());
        }
        for (Class<?> k = raw; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers()) || f.isSynthetic()) {
                    continue;
                }
                Method getter = getter(raw, f.getName());
                boolean exposed = Modifier.isPublic(f.getModifiers()) || getter != null || annotated(f, "JsonProperty");
                if (!exposed) {
                    continue;
                }
                AnnotatedElement[] sources = getter == null ? new AnnotatedElement[]{f} : new AnnotatedElement[]{f, getter};
                add(found, ignored, f.getName(), ResolvableType.forField(f, type), sources, f.getType().isPrimitive());
            }
        }
        for (Method m : raw.getMethods()) { // getters without a field (computed values) and annotated renames
            String name = propertyName(m);
            if (name != null && !found.containsKey(name) && !byRenamedField(found, m)) {
                add(found, ignored, name, ResolvableType.forMethodReturnType(m, raw), new AnnotatedElement[]{m},
                        m.getReturnType().isPrimitive());
            }
        }
        return new ArrayList<>(found.values());
    }

    private static boolean byRenamedField(Map<String, Property> found, Method getter) {
        String base = baseName(getter);
        return base != null && found.values().stream().anyMatch(p -> p.sources()[0] instanceof Field f && f.getName().equals(base));
    }

    private void add(Map<String, Property> found, Set<String> ignored, String javaName, ResolvableType type,
                     AnnotatedElement[] sources, boolean primitive) {
        if (anyAnnotated(sources, "JsonIgnore") && !jsonIgnoreFalse(sources)) {
            return;
        }
        String access = jsonPropertyAccess(sources);
        if (access != null && (direction == Direction.RESPONSE && access.equals("WRITE_ONLY")
                || direction == Direction.REQUEST && access.equals("READ_ONLY"))) {
            return;
        }
        String name = jsonName(sources, javaName);
        if (ignored.contains(name) || ignored.contains(javaName)) {
            return;
        }
        found.putIfAbsent(name, new Property(name, type, sources, primitive));
    }

    private static @Nullable Method getter(Class<?> raw, String field) {
        String cap = Character.toUpperCase(field.charAt(0)) + field.substring(1);
        for (String prefix : List.of("get", "is")) {
            try {
                Method m = raw.getMethod(prefix + cap);
                if (!Modifier.isStatic(m.getModifiers())) {
                    return m;
                }
            } catch (NoSuchMethodException e) {
                // try the next prefix
            }
        }
        return null;
    }

    private static @Nullable String baseName(Method m) {
        String n = m.getName();
        if (m.getParameterCount() != 0 || Modifier.isStatic(m.getModifiers()) || m.getReturnType() == void.class
                || m.getDeclaringClass() == Object.class) {
            return null;
        }
        if (n.startsWith("get") && n.length() > 3 && !n.equals("getClass")) {
            return Character.toLowerCase(n.charAt(3)) + n.substring(4);
        }
        if (n.startsWith("is") && n.length() > 2 && (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class)) {
            return Character.toLowerCase(n.charAt(2)) + n.substring(3);
        }
        return null;
    }

    private static @Nullable String propertyName(Method m) {
        return baseName(m);
    }

    private static @Nullable Field field(Class<?> raw, String name) {
        try {
            return raw.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    // ── Jackson annotations, by name (jackson-annotations is on every Jackson host's classpath) ────────

    private static @Nullable Annotation find(AnnotatedElement[] sources, String simpleName) {
        for (AnnotatedElement e : sources) {
            if (e == null) {
                continue;
            }
            for (Annotation a : e.getAnnotations()) {
                if (a.annotationType().getSimpleName().equals(simpleName)
                        && a.annotationType().getName().startsWith("com.fasterxml.jackson.annotation.")) {
                    return a;
                }
            }
        }
        return null;
    }

    private static boolean annotated(AnnotatedElement e, String simpleName) {
        return find(new AnnotatedElement[]{e}, simpleName) != null;
    }

    private static boolean anyAnnotated(AnnotatedElement[] sources, String simpleName) {
        return find(sources, simpleName) != null;
    }

    private static boolean jsonIgnoreFalse(AnnotatedElement[] sources) {
        Annotation a = find(sources, "JsonIgnore");
        return a != null && Boolean.FALSE.equals(call(a, "value"));
    }

    private static String jsonName(AnnotatedElement[] sources, String javaName) {
        Annotation a = find(sources, "JsonProperty");
        Object v = a == null ? null : call(a, "value");
        return v instanceof String s && !s.isEmpty() ? s : javaName;
    }

    private static boolean jsonRequired(AnnotatedElement[] sources) {
        Annotation a = find(sources, "JsonProperty");
        return a != null && Boolean.TRUE.equals(call(a, "required"));
    }

    private static @Nullable String jsonPropertyAccess(AnnotatedElement[] sources) {
        Annotation a = find(sources, "JsonProperty");
        Object v = a == null ? null : call(a, "access");
        return v == null || v.toString().equals("AUTO") ? null : v.toString();
    }

    private static List<String> ignoredNames(Class<?> raw) {
        Annotation a = find(new AnnotatedElement[]{raw}, "JsonIgnoreProperties");
        Object v = a == null ? null : call(a, "value");
        return v instanceof String[] names ? Arrays.asList(names) : List.of();
    }

    static @Nullable Object call(Annotation a, String attribute) {
        try {
            return a.annotationType().getMethod(attribute).invoke(a);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
