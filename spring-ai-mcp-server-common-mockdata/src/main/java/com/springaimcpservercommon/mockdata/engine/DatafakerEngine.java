package com.springaimcpservercommon.mockdata.engine;

import com.springaimcpservercommon.annotations.CustomFakeData;
import com.springaimcpservercommon.annotations.CustomFakerProvider;
import com.springaimcpservercommon.annotations.FakeAi;
import com.springaimcpservercommon.annotations.FakeData;
import com.springaimcpservercommon.annotations.FakeIgnore;
import com.springaimcpservercommon.mockdata.MockDataEngine;
import net.datafaker.Faker;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Fills records and plain classes from their annotations and, where there are none, from field names and types, using
 * <a href="https://www.datafaker.net">Datafaker</a>. Never throws for a field it cannot produce: it leaves a placeholder
 * and reports a {@link FieldGap} so a fallback (the AI) can do better.
 *
 * <p>Thread-safe; a seed makes the sequence of values reproducible for single-threaded use.
 */
public final class DatafakerEngine implements MockDataEngine {

    /**
     * Engine settings.
     *
     * @param locale             default locale of generated text
     * @param seed               seed of the random source, or {@code null} for a random one
     * @param maxDepth           deepest nesting of objects inside objects
     * @param maxCollectionSize  most elements generated for a collection field (at least one)
     * @param aiForUnannotated   report every unannotated leaf field as an AI gap (AI mode); placeholders still get heuristic values
     * @param providers          creates {@link CustomFakerProvider}s (a Spring bean lookup, or reflection)
     */
    public record Options(Locale locale, @Nullable Long seed, int maxDepth, int maxCollectionSize, boolean aiForUnannotated,
                          Function<Class<? extends CustomFakerProvider>, CustomFakerProvider> providers) {

        /** Validates. */
        public Options {
            Objects.requireNonNull(locale, "locale");
            Objects.requireNonNull(providers, "providers");
            if (maxDepth < 1 || maxCollectionSize < 1) {
                throw new IllegalArgumentException("maxDepth and maxCollectionSize must be at least 1");
            }
        }

        /**
         * Defaults: English, random seed, depth 4, up to 3 elements, heuristics for unannotated fields, reflective providers.
         *
         * @return options
         */
        public static Options defaults() {
            return new Options(Locale.ENGLISH, null, 4, 3, false, DatafakerEngine::instantiate);
        }

        /**
         * Copy with a seed.
         *
         * @param newSeed the seed
         * @return options
         */
        public Options withSeed(@Nullable Long newSeed) {
            return new Options(locale, newSeed, maxDepth, maxCollectionSize, aiForUnannotated, providers);
        }
    }

    /** Where a field's annotations live: the field itself and, for a record, its component. */
    private record Src(String name, Type type, AnnotatedElement... elements) {
        <A extends Annotation> @Nullable A ann(Class<A> a) {
            for (AnnotatedElement e : elements) {
                A found = e.getAnnotation(a);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
    }

    private record Ctx(Map<String, Object> overrides, List<FieldGap> gaps) {
    }

    private final Options options;
    private final Random random;
    private final Map<Locale, Faker> fakers = new ConcurrentHashMap<>();
    private final Map<Class<?>, CustomFakerProvider> providerCache = new ConcurrentHashMap<>();

    /**
     * Creates an engine.
     *
     * @param options settings
     */
    public DatafakerEngine(Options options) {
        this.options = options;
        this.random = options.seed() == null ? new Random() : new Random(options.seed());
    }

    @Override
    public <T> T generate(Class<T> type) {
        return generate(type, Map.of()).value();
    }

    /**
     * Generates an instance and reports the fields that are only placeholders.
     *
     * @param type      class with a no-argument constructor, or a record
     * @param overrides values to use instead of generating, by dotted path ({@code address.city}); used for AI-supplied values
     * @param <T>       the type
     * @return the instance and its gaps
     */
    @SuppressWarnings("unchecked")
    public <T> Generated<T> generate(Class<T> type, Map<String, Object> overrides) {
        Ctx ctx = new Ctx(overrides, new ArrayList<>());
        Object value = object(type, "", 0, ctx);
        if (value == null) {
            throw new IllegalArgumentException(type.getName() + " cannot be instantiated: use a record or a class with a no-argument constructor");
        }
        return new Generated<>((T) value, ctx.gaps());
    }

    // ------------------------------------------------------------------------------------------------ objects

    private @Nullable Object object(Class<?> type, String path, int depth, Ctx ctx) {
        if (depth >= options.maxDepth()) {
            ctx.gaps().add(new FieldGap(path, type, FieldGap.Reason.DEPTH_LIMIT, ""));
            return null;
        }
        try {
            if (type.isRecord()) {
                RecordComponent[] comps = type.getRecordComponents();
                Class<?>[] types = new Class<?>[comps.length];
                Object[] args = new Object[comps.length];
                for (int i = 0; i < comps.length; i++) {
                    types[i] = comps[i].getType();
                    Src src = new Src(comps[i].getName(), comps[i].getGenericType(), comps[i], field(type, comps[i].getName()));
                    Object v = value(src, join(path, comps[i].getName()), depth + 1, ctx);
                    args[i] = v != null ? v : TypeSupport.defaultFor(types[i]);
                }
                Constructor<?> c = type.getDeclaredConstructor(types);
                c.setAccessible(true);
                return c.newInstance(args);
            }
            if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
                ctx.gaps().add(new FieldGap(path, type, FieldGap.Reason.UNSUPPORTED_TYPE, ""));
                return null;
            }
            Constructor<?> c = type.getDeclaredConstructor();
            c.setAccessible(true);
            Object instance = c.newInstance();
            for (Class<?> k = type; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || Modifier.isFinal(f.getModifiers()) || Modifier.isTransient(f.getModifiers())) {
                        continue;
                    }
                    Object v = value(new Src(f.getName(), f.getGenericType(), f), join(path, f.getName()), depth + 1, ctx);
                    if (v != null) {
                        f.setAccessible(true);
                        f.set(instance, v);
                    }
                }
            }
            return instance;
        } catch (ReflectiveOperationException | RuntimeException e) {
            ctx.gaps().add(new FieldGap(path, type, FieldGap.Reason.UNSUPPORTED_TYPE, ""));
            return null;
        }
    }

    private static @Nullable Field field(Class<?> type, String name) {
        try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static String join(String path, String name) {
        return path.isEmpty() ? name : path + "." + name;
    }

    // ------------------------------------------------------------------------------------------------ fields

    private @Nullable Object value(Src s, String path, int depth, Ctx ctx) {
        Class<?> raw = TypeSupport.raw(s.type());
        if (ctx.overrides().containsKey(path)) {
            return ctx.overrides().get(path);
        }
        if (s.ann(FakeIgnore.class) != null) {
            return TypeSupport.defaultFor(raw);
        }
        CustomFakeData custom = s.ann(CustomFakeData.class);
        if (custom != null) {
            return custom(custom, s, raw, path, ctx);
        }
        FakeAi ai = s.ann(FakeAi.class);
        if (ai != null) {
            ctx.gaps().add(new FieldGap(path, raw, FieldGap.Reason.AI_REQUESTED, ai.value()));
            return byType(s.type(), s.name(), path, depth, ctx);
        }
        FakeData fake = s.ann(FakeData.class);
        if (fake != null) {
            return fake(fake, s, raw, path, depth, ctx);
        }
        if (options.aiForUnannotated() && isLeafField(s.type())) {
            ctx.gaps().add(new FieldGap(path, raw, FieldGap.Reason.AI_REQUESTED, ""));
        }
        return byType(s.type(), s.name(), path, depth, ctx);
    }

    private boolean isLeafField(Type t) {
        Class<?> raw = TypeSupport.raw(t);
        if (Collection.class.isAssignableFrom(raw) || raw == Optional.class) {
            return TypeSupport.leaf(TypeSupport.raw(TypeSupport.arg(t, 0)));
        }
        return TypeSupport.leaf(raw);
    }

    private @Nullable Object custom(CustomFakeData a, Src s, Class<?> raw, String path, Ctx ctx) {
        try {
            CustomFakerProvider provider = providerCache.computeIfAbsent(a.provider(), k -> options.providers().apply(a.provider()));
            Object v = provider.provide(random);
            if (v == null || TypeSupport.wrapper(raw).isInstance(v)) {
                return v;
            }
            Object converted = TypeSupport.coerce(String.valueOf(v), raw);
            if (converted == null) {
                ctx.gaps().add(new FieldGap(path, raw, FieldGap.Reason.EXPRESSION_FAILED, ""));
            }
            return converted;
        } catch (RuntimeException e) {
            ctx.gaps().add(new FieldGap(path, raw, FieldGap.Reason.EXPRESSION_FAILED, ""));
            return null;
        }
    }

    private @Nullable Object fake(FakeData a, Src s, Class<?> raw, String path, int depth, Ctx ctx) {
        Faker faker = faker(a.locale().isBlank() ? options.locale() : Locale.forLanguageTag(a.locale()));
        if (Collection.class.isAssignableFrom(raw)) {
            Class<?> element = TypeSupport.raw(TypeSupport.arg(s.type(), 0));
            Collection<Object> out = raw.isAssignableFrom(ArrayList.class) ? new ArrayList<>() : new LinkedHashSet<>();
            int n = 1 + random.nextInt(options.maxCollectionSize());
            for (int i = 0; i < n; i++) {
                Object v = expression(faker, a.value(), element, path, ctx);
                if (v != null) {
                    out.add(v);
                }
            }
            return out;
        }
        Object v = expression(faker, a.value(), raw, path, ctx);
        return v != null ? v : byType(s.type(), s.name(), path, depth, ctx);
    }

    private @Nullable Object expression(Faker faker, String expr, Class<?> target, String path, Ctx ctx) {
        try {
            Object v = TypeSupport.coerce(faker.expression("#{" + expr + "}"), target);
            if (v == null) {
                ctx.gaps().add(new FieldGap(path, target, FieldGap.Reason.EXPRESSION_FAILED, ""));
            }
            return v;
        } catch (RuntimeException e) {
            ctx.gaps().add(new FieldGap(path, target, FieldGap.Reason.EXPRESSION_FAILED, ""));
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------------ by type

    @SuppressWarnings({"unchecked", "rawtypes"})
    private @Nullable Object byType(Type type, String name, String path, int depth, Ctx ctx) {
        Class<?> raw = TypeSupport.raw(type);
        Faker faker = faker(options.locale());
        String n = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (raw == String.class || raw == CharSequence.class || raw == Object.class) {
            return string(faker, n);
        }
        if (raw == int.class || raw == Integer.class) {
            return (int) number(n, 1, 1000);
        }
        if (raw == long.class || raw == Long.class) {
            return number(n, 1, 100_000);
        }
        if (raw == short.class || raw == Short.class) {
            return (short) number(n, 1, 1000);
        }
        if (raw == byte.class || raw == Byte.class) {
            return (byte) number(n, 1, 100);
        }
        if (raw == double.class || raw == Double.class) {
            return decimal(n);
        }
        if (raw == float.class || raw == Float.class) {
            return (float) decimal(n);
        }
        if (raw == BigDecimal.class) {
            return BigDecimal.valueOf(decimal(n)).setScale(2, java.math.RoundingMode.HALF_UP);
        }
        if (raw == BigInteger.class) {
            return BigInteger.valueOf(number(n, 1, 100_000));
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return random.nextBoolean();
        }
        if (raw == char.class || raw == Character.class) {
            return (char) ('a' + random.nextInt(26));
        }
        if (raw == UUID.class) {
            return new UUID(random.nextLong(), random.nextLong());
        }
        if (raw == URI.class) {
            return URI.create(faker.internet().url().startsWith("http") ? faker.internet().url() : "https://" + faker.internet().url());
        }
        if (raw == Locale.class) {
            return options.locale();
        }
        if (raw.isEnum()) {
            Object[] all = raw.getEnumConstants();
            return all.length == 0 ? null : all[random.nextInt(all.length)];
        }
        if (raw == LocalDate.class) {
            return LocalDate.now().minusDays(random.nextInt(3650));
        }
        if (raw == LocalDateTime.class) {
            return LocalDateTime.now(ZoneOffset.UTC).minusSeconds(random.nextInt(86_400 * 365));
        }
        if (raw == LocalTime.class) {
            return LocalTime.ofSecondOfDay(random.nextInt(86_400));
        }
        if (raw == Instant.class) {
            return Instant.now().minusSeconds(random.nextInt(86_400 * 365));
        }
        if (raw == OffsetDateTime.class) {
            return OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(random.nextInt(86_400 * 365));
        }
        if (raw == ZonedDateTime.class) {
            return ZonedDateTime.now(ZoneOffset.UTC).minusSeconds(random.nextInt(86_400 * 365));
        }
        if (raw == Duration.class) {
            return Duration.ofMinutes(1 + random.nextInt(600));
        }
        if (raw == Optional.class) {
            return Optional.ofNullable(byType(TypeSupport.arg(type, 0), name, path, depth, ctx));
        }
        if (raw.isArray()) {
            int len = 1 + random.nextInt(options.maxCollectionSize());
            Object arr = java.lang.reflect.Array.newInstance(raw.getComponentType(), len);
            for (int i = 0; i < len; i++) {
                Object v = byType(raw.getComponentType(), name, path, depth, ctx);
                if (v != null) {
                    java.lang.reflect.Array.set(arr, i, v);
                }
            }
            return arr;
        }
        if (Collection.class.isAssignableFrom(raw)) {
            Type element = TypeSupport.arg(type, 0);
            Collection<Object> out = Set.class.isAssignableFrom(raw) ? new LinkedHashSet<>() : new ArrayList<>();
            int len = 1 + random.nextInt(options.maxCollectionSize());
            for (int i = 0; i < len; i++) {
                Object v = byType(element, name, path, depth, ctx);
                if (v != null) {
                    out.add(v);
                }
            }
            return out;
        }
        if (Map.class.isAssignableFrom(raw)) {
            Map<Object, Object> out = new LinkedHashMap<>();
            int len = 1 + random.nextInt(options.maxCollectionSize());
            for (int i = 0; i < len; i++) {
                Object k = byType(TypeSupport.arg(type, 0), "key", path, depth, ctx);
                Object v = byType(TypeSupport.arg(type, 1), name, path, depth, ctx);
                if (k != null && v != null) {
                    out.put(k, v);
                }
            }
            return out;
        }
        return object(raw, path, depth, ctx);
    }

    private String string(Faker f, String n) {
        if (n.contains("email")) {
            return f.internet().emailAddress();
        }
        if (n.contains("firstname") || n.contains("givenname")) {
            return f.name().firstName();
        }
        if (n.contains("lastname") || n.contains("surname") || n.contains("familyname")) {
            return f.name().lastName();
        }
        if (n.contains("username") || n.equals("login")) {
            return f.internet().username();
        }
        if (n.equals("name") || n.contains("fullname") || n.endsWith("customername") || n.equals("owner")) {
            return f.name().fullName();
        }
        if (n.contains("phone") || n.contains("mobile")) {
            return f.phoneNumber().cellPhone();
        }
        if (n.contains("city") || n.contains("town")) {
            return f.address().city();
        }
        if (n.contains("country")) {
            return f.address().country();
        }
        if (n.contains("street") || n.contains("address")) {
            return f.address().streetAddress();
        }
        if (n.contains("zip") || n.contains("postal")) {
            return f.address().zipCode();
        }
        if (n.equals("state") || n.contains("province")) {
            return f.address().state();
        }
        if (n.contains("company") || n.contains("organization") || n.contains("organisation") || n.contains("employer")) {
            return f.company().name();
        }
        if (n.contains("url") || n.contains("website") || n.contains("link")) {
            return f.internet().url();
        }
        if (n.contains("iban")) {
            return f.finance().iban();
        }
        if (n.contains("currency")) {
            return f.currency().code();
        }
        if (n.contains("jobtitle") || n.equals("title") || n.equals("position")) {
            return f.job().title();
        }
        if (n.contains("password") || n.contains("secret")) {
            return f.internet().password();
        }
        if (n.equals("ip") || n.contains("ipaddress")) {
            return f.internet().ipV4Address();
        }
        if (n.contains("uuid") || n.contains("guid") || n.equals("id") || n.endsWith("id")) {
            return new UUID(random.nextLong(), random.nextLong()).toString();
        }
        if (n.contains("description") || n.contains("comment") || n.contains("note") || n.contains("message") || n.contains("summary")) {
            return f.lorem().sentence();
        }
        return f.lorem().word();
    }

    private long number(String n, long lo, long hi) {
        if (n.equals("age")) {
            return 18 + random.nextInt(63);
        }
        if (n.contains("year")) {
            return 1990 + random.nextInt(41);
        }
        if (n.contains("quantity") || n.equals("qty") || n.contains("count")) {
            return 1 + random.nextInt(20);
        }
        return lo + (long) (random.nextDouble() * (hi - lo + 1));
    }

    private double decimal(String n) {
        double hi = n.contains("price") || n.contains("amount") || n.contains("total") || n.contains("balance") ? 1000 : 100;
        return Math.round((1 + random.nextDouble() * hi) * 100) / 100.0;
    }

    private Faker faker(Locale locale) {
        return fakers.computeIfAbsent(locale, l -> new Faker(l, random));
    }

    private static CustomFakerProvider instantiate(Class<? extends CustomFakerProvider> type) {
        try {
            Constructor<? extends CustomFakerProvider> c = type.getDeclaredConstructor();
            c.setAccessible(true);
            return c.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("custom faker provider " + type.getName() + " needs a no-argument constructor", e);
        }
    }
}
