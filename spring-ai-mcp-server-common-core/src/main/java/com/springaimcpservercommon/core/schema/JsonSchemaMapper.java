package com.springaimcpservercommon.core.schema;

import com.springaimcpservercommon.annotations.AiContext;
import com.springaimcpservercommon.annotations.AiEntityProperty;
import com.springaimcpservercommon.core.catalog.JsonSchema;
import com.springaimcpservercommon.core.lint.SensitiveNames;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Currency;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.BaseStream;

/**
 * Spring-free mapper from Java types to JSON Schema (draft 2020-12 vocabulary), used for tool input schemas and
 * return schemas (LLD-02 §3.4, LLD-14 §3.2).
 *
 * <p>Mapping: booleans; integral primitives/boxes → {@code integer} (with {@code int32}/{@code int64} formats);
 * floating point → {@code number}; {@code char}/{@code String}/{@code CharSequence} → {@code string}; enums →
 * {@code string} + {@code enum}; {@code BigDecimal}/{@code BigInteger} → {@code string} with {@code format}
 * {@code decimal}/{@code integer} and a pattern (no precision loss in JSON numbers); {@code java.time} types,
 * {@code UUID}, {@code URI}/{@code URL}, {@code Locale}, {@code Currency} → formatted strings; {@code Optional<T>}
 * → schema of {@code T}; arrays, collections and streams → {@code array} with a {@code maxItems} hint ({@code Set}
 * adds {@code uniqueItems}); {@code byte[]} → base64 string; {@code Map<String,V>} → {@code object} with
 * {@code additionalProperties}; records and POJOs (public getters / public fields) → {@code object}, recursively,
 * with cycle-safe {@code $ref: "#/$defs/<fqcn>"} and a depth limit. Spring Data {@code Pageable} → {@code {page,
 * size}}, {@code Limit} → integer, {@code Page}/{@code Slice}/{@code Window} → {@code {content[], hasNext}} (by name).
 *
 * <p>Decoration: {@code @AiEntityProperty.meaning} becomes the member {@code description}, a type-level
 * {@code @AiContext.description} the object description. Members that are {@code sensitive=true},
 * {@code @JsonIgnore}d, {@code @Transient} or {@code transient}, and members whose name trips the sensitive-name
 * heuristic without confirmation, are removed and reported.
 *
 * <p>Thread-safe and stateless apart from its options.
 */
public final class JsonSchemaMapper {

    /**
     * Mapper options.
     *
     * @param maxDepth        maximum object nesting that is expanded (deeper objects become an opaque object and
     *                        set {@link SchemaResult#depthLimitReached()})
     * @param defaultMaxItems {@code maxItems} hint for arrays and collections
     * @param sensitiveNames  sensitive-name heuristic for members
     */
    public record Options(int maxDepth, int defaultMaxItems, SensitiveNames sensitiveNames) {

        /** Validates components. */
        public Options {
            if (maxDepth < 1) {
                throw new IllegalArgumentException("maxDepth must be >= 1");
            }
            if (defaultMaxItems < 1) {
                throw new IllegalArgumentException("defaultMaxItems must be >= 1");
            }
            Objects.requireNonNull(sensitiveNames, "sensitiveNames");
        }

        /**
         * Defaults: depth 5, maxItems 100, default sensitive names.
         *
         * @return default options
         */
        public static Options defaults() {
            return new Options(5, 100, SensitiveNames.defaults());
        }
    }

    private static final Set<String> IGNORE_ANNOTATIONS = Set.of(
            "com.fasterxml.jackson.annotation.JsonIgnore",
            "jakarta.persistence.Transient",
            "java.beans.Transient",
            "org.springframework.data.annotation.Transient");

    private final Options options;

    /**
     * Creates a mapper.
     *
     * @param options options
     */
    public JsonSchemaMapper(Options options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    /**
     * Schema of a single type (e.g. an action's return type).
     *
     * @param type     generic type
     * @param bindings type-variable bindings of the declaring bean class ({@link GenericTypes#bindingsOf})
     * @return the result
     */
    public SchemaResult schemaFor(Type type, Map<TypeVariable<?>, Type> bindings) {
        Ctx ctx = new Ctx();
        Map<String, Object> root = new LinkedHashMap<>(map(type, bindings, ctx, 0, "$"));
        return ctx.finish(root);
    }

    /**
     * Input schema of a tool: an object with one property per parameter, {@code required} and
     * {@code additionalProperties: false}.
     *
     * @param parameters parameters in declaration order
     * @param bindings   type-variable bindings of the declaring bean class
     * @return the result
     */
    public SchemaResult inputSchema(List<ParameterSpec> parameters, Map<TypeVariable<?>, Type> bindings) {
        Ctx ctx = new Ctx();
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (ParameterSpec p : parameters) {
            Map<String, Object> schema = new LinkedHashMap<>(map(p.type(), bindings, ctx, 0, p.name()));
            if (p.description() != null && !p.description().isBlank()) {
                schema.put("description", p.description());
            }
            properties.put(p.name(), schema);
            if (p.required()) {
                required.add(p.name());
            }
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", properties);
        if (!required.isEmpty()) {
            root.put("required", required);
        }
        root.put("additionalProperties", false);
        return ctx.finish(root);
    }

    // ---------------------------------------------------------------------------------------------------------

    private final class Ctx {
        final Deque<Class<?>> path = new ArrayDeque<>();
        final Map<Class<?>, Map<TypeVariable<?>, Type>> needDef = new LinkedHashMap<>();
        final Map<String, Object> defs = new TreeMap<>();
        int maxNesting;
        boolean containsMap;
        boolean polymorphic;
        boolean depthLimit;
        final List<String> removed = new ArrayList<>();
        final List<String> unconfirmed = new ArrayList<>();

        SchemaResult finish(Map<String, Object> root) {
            // generate $defs for recursive types; generating one may require more
            java.util.Set<Class<?>> done = new java.util.HashSet<>();
            boolean progress = true;
            while (progress) {
                progress = false;
                for (Map.Entry<Class<?>, Map<TypeVariable<?>, Type>> e : new ArrayList<>(needDef.entrySet())) {
                    if (done.add(e.getKey())) {
                        progress = true;
                        path.clear();
                        defs.put(e.getKey().getName(), object(e.getKey(), e.getValue(), this, 0, e.getKey().getSimpleName()));
                    }
                }
            }
            if (!defs.isEmpty()) {
                root.put("$defs", defs);
            }
            return new SchemaResult(JsonSchema.of(root), maxNesting, containsMap, polymorphic, depthLimit, removed,
                    unconfirmed);
        }
    }

    private Map<String, Object> map(Type type, Map<TypeVariable<?>, Type> bindings, Ctx ctx, int nesting,
                                    String path) {
        Type t = GenericTypes.resolve(type, bindings);
        if (t instanceof WildcardType w) {
            return map(w.getUpperBounds()[0], bindings, ctx, nesting, path);
        }
        if (t instanceof TypeVariable<?> v) {
            return map(v.getBounds()[0], bindings, ctx, nesting, path);
        }
        if (t instanceof GenericArrayType g) {
            return array(g.getGenericComponentType(), bindings, ctx, nesting, path, false);
        }
        Class<?> raw = GenericTypes.raw(t, bindings);
        Type[] args = t instanceof ParameterizedType p ? p.getActualTypeArguments() : new Type[0];

        Map<String, Object> scalar = scalar(raw);
        if (scalar != null) {
            return scalar;
        }
        if (raw.isEnum()) {
            List<String> names = new ArrayList<>();
            for (Object constant : Objects.requireNonNull(raw.getEnumConstants())) {
                names.add(((Enum<?>) constant).name());
            }
            return Map.of("type", "string", "enum", names);
        }
        if (raw == byte[].class) {
            return Map.of("type", "string", "contentEncoding", "base64");
        }
        if (raw.isArray()) {
            return array(Objects.requireNonNull(raw.getComponentType()), bindings, ctx, nesting, path, false);
        }
        if (raw == Optional.class) {
            return map(args.length == 1 ? args[0] : Object.class, bindings, ctx, nesting, path);
        }
        if (SpringDataTypes.isA(raw, SpringDataTypes.PAGEABLE)) {
            ctx.maxNesting = Math.max(ctx.maxNesting, nesting + 1);
            return Map.of("type", "object", "properties", Map.of(
                            "page", Map.of("type", "integer", "minimum", 0, "description", "zero-based page index"),
                            "size", Map.of("type", "integer", "minimum", 1, "description", "page size")),
                    "additionalProperties", false);
        }
        if (SpringDataTypes.isA(raw, SpringDataTypes.LIMIT)) {
            return Map.of("type", "integer", "minimum", 1, "description", "maximum number of results");
        }
        if (SpringDataTypes.isA(raw, SpringDataTypes.SORT)) {
            return Map.of("type", "string", "description", "sort expression, e.g. property,asc");
        }
        if (SpringDataTypes.isPagedResult(raw)) {
            Type element = args.length == 1 ? args[0] : Object.class;
            ctx.maxNesting = Math.max(ctx.maxNesting, nesting + 1);
            return Map.of("type", "object", "properties", Map.of(
                    "content", array(element, bindings, ctx, nesting + 1, path + ".content", false),
                    "hasNext", Map.of("type", "boolean")));
        }
        if (Map.class.isAssignableFrom(raw)) {
            ctx.containsMap = true;
            ctx.maxNesting = Math.max(ctx.maxNesting, nesting + 1);
            Type valueType = args.length == 2 ? args[1] : Object.class;
            return Map.of("type", "object", "additionalProperties", map(valueType, bindings, ctx, nesting + 1, path + "{}"));
        }
        if (Collection.class.isAssignableFrom(raw) || Iterable.class.isAssignableFrom(raw)
                || BaseStream.class.isAssignableFrom(raw)) {
            Type element = args.length == 1 ? args[0] : Object.class;
            return array(element, bindings, ctx, nesting, path, Set.class.isAssignableFrom(raw));
        }
        if (raw == Object.class || raw.isInterface() || Modifier.isAbstract(raw.getModifiers()) || raw.isPrimitive()
                || isJdkType(raw)) {
            ctx.polymorphic = true;
            return Map.of("type", "object");
        }
        // record or POJO
        Map<TypeVariable<?>, Type> own = new HashMap<>(bindings);
        TypeVariable<?>[] vars = raw.getTypeParameters();
        for (int i = 0; i < vars.length && i < args.length; i++) {
            own.put(vars[i], GenericTypes.resolve(args[i], bindings));
        }
        if (ctx.path.contains(raw)) {
            ctx.needDef.putIfAbsent(raw, own);
            return Map.of("$ref", "#/$defs/" + raw.getName());
        }
        if (nesting >= options.maxDepth()) {
            ctx.depthLimit = true;
            return Map.of("type", "object", "description", "nested too deeply; omitted");
        }
        return object(raw, own, ctx, nesting, path);
    }

    private Map<String, Object> array(Type element, Map<TypeVariable<?>, Type> bindings, Ctx ctx, int nesting,
                                      String path, boolean unique) {
        ctx.maxNesting = Math.max(ctx.maxNesting, nesting + 1);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", map(element, bindings, ctx, nesting + 1, path + "[]"));
        schema.put("maxItems", options.defaultMaxItems());
        if (unique) {
            schema.put("uniqueItems", true);
        }
        return schema;
    }

    private Map<String, Object> object(Class<?> raw, Map<TypeVariable<?>, Type> bindings, Ctx ctx, int nesting,
                                       String path) {
        ctx.maxNesting = Math.max(ctx.maxNesting, nesting + 1);
        ctx.path.push(raw);
        try {
            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (Member m : members(raw)) {
                String memberPath = path + "." + m.name();
                AiEntityProperty prop = m.property();
                if (m.ignored() || (prop != null && prop.sensitive())) {
                    ctx.removed.add(memberPath);
                    continue;
                }
                // a sensitive-looking name that is not sensitive=true must be confirmed (copy-paste guard, LLD-02 §4)
                if (options.sensitiveNames().check(raw.getName(), m.name()) == SensitiveNames.Verdict.UNCONFIRMED) {
                    ctx.unconfirmed.add(memberPath);
                    continue;
                }
                Map<String, Object> schema = new LinkedHashMap<>(map(m.type(), bindings, ctx, nesting + 1, memberPath));
                if (prop != null && !prop.meaning().isBlank()) {
                    schema.put("description", prop.meaning());
                }
                properties.put(m.name(), schema);
                if (m.required()) {
                    required.add(m.name());
                }
            }
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            AiContext context = raw.getAnnotation(AiContext.class);
            if (context != null && !context.description().isBlank()) {
                schema.put("description", context.description());
            }
            schema.put("properties", properties);
            if (!required.isEmpty()) {
                required.sort(null);
                schema.put("required", required);
            }
            schema.put("additionalProperties", false);
            return schema;
        } finally {
            ctx.path.pop();
        }
    }

    private record Member(String name, Type type, @Nullable AiEntityProperty property, boolean ignored,
                          boolean required) {
    }

    private static List<Member> members(Class<?> raw) {
        List<Member> out = new ArrayList<>();
        if (raw.isRecord()) {
            for (RecordComponent rc : Objects.requireNonNull(raw.getRecordComponents())) {
                boolean nullable = hasNullable(rc) || hasNullable(rc.getAnnotatedType());
                Class<?> type = rc.getType();
                boolean required = type.isPrimitive() || (type != Optional.class && type != OptionalInt.class
                        && type != OptionalLong.class && type != OptionalDouble.class && !nullable);
                out.add(new Member(rc.getName(), rc.getGenericType(), rc.getAnnotation(AiEntityProperty.class),
                        ignored(rc) || ignored(rc.getAccessor()), required));
            }
            return out;
        }
        Map<String, Member> byName = new TreeMap<>();
        for (Method m : raw.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0 || m.isBridge() || m.isSynthetic()
                    || m.getDeclaringClass() == Object.class || m.getReturnType() == void.class) {
                continue;
            }
            String name = propertyName(m);
            if (name == null) {
                continue;
            }
            Field field = findField(raw, name);
            AiEntityProperty prop = m.getAnnotation(AiEntityProperty.class);
            if (prop == null && field != null) {
                prop = field.getAnnotation(AiEntityProperty.class);
            }
            boolean ignored = ignored(m) || (field != null && (ignored(field) || Modifier.isTransient(field.getModifiers())));
            byName.put(name, new Member(name, m.getGenericReturnType(), prop, ignored, m.getReturnType().isPrimitive()));
        }
        for (Field f : raw.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) || byName.containsKey(f.getName())) {
                continue;
            }
            byName.put(f.getName(), new Member(f.getName(), f.getGenericType(), f.getAnnotation(AiEntityProperty.class),
                    ignored(f) || Modifier.isTransient(f.getModifiers()), f.getType().isPrimitive()));
        }
        out.addAll(byName.values());
        return out;
    }

    private static @Nullable String propertyName(Method m) {
        String n = m.getName();
        String base;
        if (n.startsWith("get") && n.length() > 3) {
            base = n.substring(3);
        } else if (n.startsWith("is") && n.length() > 2
                && (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class)) {
            base = n.substring(2);
        } else {
            return null;
        }
        if (!Character.isUpperCase(base.charAt(0))) {
            return null;
        }
        if (base.length() > 1 && Character.isUpperCase(base.charAt(1))) {
            return base; // JavaBeans: "URL" stays "URL"
        }
        return Character.toLowerCase(base.charAt(0)) + base.substring(1);
    }

    private static @Nullable Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                // continue with the superclass
            }
        }
        return null;
    }

    private static boolean ignored(AnnotatedElement element) {
        for (Annotation a : element.getAnnotations()) {
            if (IGNORE_ANNOTATIONS.contains(a.annotationType().getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNullable(AnnotatedElement element) {
        for (Annotation a : element.getAnnotations()) {
            if (a.annotationType().getSimpleName().equals("Nullable")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isJdkType(Class<?> raw) {
        String n = raw.getName();
        return n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("jdk.") || n.startsWith("sun.");
    }

    private static @Nullable Map<String, Object> scalar(Class<?> raw) {
        if (raw == boolean.class || raw == Boolean.class) {
            return Map.of("type", "boolean");
        }
        if (raw == int.class || raw == Integer.class) {
            return Map.of("type", "integer", "format", "int32");
        }
        if (raw == long.class || raw == Long.class) {
            return Map.of("type", "integer", "format", "int64");
        }
        if (raw == short.class || raw == Short.class) {
            return Map.of("type", "integer", "minimum", (int) Short.MIN_VALUE, "maximum", (int) Short.MAX_VALUE);
        }
        if (raw == byte.class || raw == Byte.class) {
            return Map.of("type", "integer", "minimum", (int) Byte.MIN_VALUE, "maximum", (int) Byte.MAX_VALUE);
        }
        if (raw == float.class || raw == Float.class) {
            return Map.of("type", "number", "format", "float");
        }
        if (raw == double.class || raw == Double.class) {
            return Map.of("type", "number", "format", "double");
        }
        if (raw == char.class || raw == Character.class) {
            return Map.of("type", "string", "minLength", 1, "maxLength", 1);
        }
        if (raw == String.class || raw == CharSequence.class) {
            return Map.of("type", "string");
        }
        if (raw == BigDecimal.class) {
            return Map.of("type", "string", "format", "decimal", "pattern", "^-?\\d+(\\.\\d+)?$");
        }
        if (raw == BigInteger.class) {
            return Map.of("type", "string", "format", "integer", "pattern", "^-?\\d+$");
        }
        if (raw == OptionalInt.class || raw == OptionalLong.class) {
            return Map.of("type", "integer");
        }
        if (raw == OptionalDouble.class) {
            return Map.of("type", "number");
        }
        if (raw == Instant.class || raw == OffsetDateTime.class || raw == ZonedDateTime.class || raw == Date.class) {
            return Map.of("type", "string", "format", "date-time");
        }
        if (raw == LocalDate.class) {
            return Map.of("type", "string", "format", "date");
        }
        if (raw == LocalDateTime.class) {
            return Map.of("type", "string", "format", "local-date-time",
                    "description", "ISO-8601 local date-time without offset, e.g. 2026-09-28T14:30:00");
        }
        if (raw == LocalTime.class) {
            return Map.of("type", "string", "format", "local-time", "description", "ISO-8601 local time, e.g. 14:30:00");
        }
        if (raw == OffsetTime.class) {
            return Map.of("type", "string", "format", "time");
        }
        if (raw == Duration.class || raw == Period.class) {
            return Map.of("type", "string", "format", "duration");
        }
        if (raw == Year.class) {
            return Map.of("type", "integer", "format", "int32");
        }
        if (raw == YearMonth.class) {
            return Map.of("type", "string", "pattern", "^\\d{4}-\\d{2}$");
        }
        if (raw == MonthDay.class) {
            return Map.of("type", "string", "pattern", "^--\\d{2}-\\d{2}$");
        }
        if (ZoneId.class.isAssignableFrom(raw) || raw == ZoneOffset.class) {
            return Map.of("type", "string", "description", "time-zone id, e.g. Europe/Berlin");
        }
        if (raw == UUID.class) {
            return Map.of("type", "string", "format", "uuid");
        }
        if (raw == URI.class || raw == URL.class) {
            return Map.of("type", "string", "format", "uri");
        }
        if (raw == Locale.class) {
            return Map.of("type", "string", "description", "BCP 47 language tag, e.g. en-US");
        }
        if (raw == Currency.class) {
            return Map.of("type", "string", "pattern", "^[A-Z]{3}$", "description", "ISO 4217 currency code");
        }
        return null;
    }
}
