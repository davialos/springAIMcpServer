package com.springaimcpservercommon.loadtest.junit;

import com.springaimcpservercommon.loadtest.api.LoadTestGenerator;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The extension behind {@link K6LoadTest}: generates the suite before the class's tests (once per class) and
 * resolves {@link K6Suite} parameters. Generation reads the project only (no database by default) and never needs
 * the application to be running; runs do.
 */
public final class K6LoadTestExtension implements BeforeAllCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(K6LoadTestExtension.class);

    /** Creates the extension (instantiated by JUnit). */
    public K6LoadTestExtension() {
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        K6LoadTest config = config(context);
        Path project = Path.of(config.project());
        if (!Files.isDirectory(project)) {
            throw new ExtensionConfigurationException("@K6LoadTest(project = \"" + config.project()
                    + "\"): not a directory (relative to " + Path.of("").toAbsolutePath() + ")");
        }
        LoadTestGenerator.Builder b = LoadTestGenerator.builder()
                .project(project)
                .outDir(Path.of(config.outDir()))
                .log(line -> System.out.println("[loadtest] " + line));
        for (String i : config.include()) {
            b.include(i);
        }
        for (String e : config.exclude()) {
            b.exclude(e);
        }
        if (!config.database()) {
            b.noDatabase();
        }
        LoadTestGenerator.GenerationResult generated = b.build().generate();
        context.getStore(NS).put(context.getRequiredTestClass(), generated);
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        return parameter.getParameter().getType() == K6Suite.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        K6LoadTest config = config(context);
        Class<?> testClass = context.getRequiredTestClass();
        LoadTestGenerator.GenerationResult generated = context.getStore(NS)
                .get(testClass, LoadTestGenerator.GenerationResult.class);
        if (generated == null) {
            throw new ParameterResolutionException("suite not generated for " + testClass.getName());
        }
        Optional<Object> instance = context.getTestInstance();
        Map<String, String> env = new LinkedHashMap<>();
        for (String e : config.env()) {
            int eq = e.indexOf('=');
            if (eq <= 0) {
                throw new ExtensionConfigurationException("@K6LoadTest(env) entries are NAME=value: " + e);
            }
            env.put(e.substring(0, eq), e.substring(eq + 1));
        }
        return new K6Suite(generated, () -> baseUrl(config, generated, testClass, instance.orElse(null)), env,
                config.requireK6());
    }

    private static K6LoadTest config(ExtensionContext context) {
        Class<?> c = context.getRequiredTestClass();
        while (c != null) {
            K6LoadTest a = c.getAnnotation(K6LoadTest.class);
            if (a != null) {
                return a;
            }
            c = c.getEnclosingClass(); // @Nested classes inherit the outer configuration
        }
        throw new ExtensionConfigurationException("no @K6LoadTest on " + context.getRequiredTestClass().getName());
    }

    /** Annotation, system property, else localhost with the test's port and the generated context path. */
    static String baseUrl(K6LoadTest config, LoadTestGenerator.GenerationResult generated, Class<?> testClass,
                          @Nullable Object instance) {
        if (!config.baseUrl().isBlank()) {
            return config.baseUrl();
        }
        String property = System.getProperty("loadtest.baseUrl");
        if (property != null && !property.isBlank()) {
            return property;
        }
        Object target = instance == null ? null : target(testClass, instance);
        String contextPath = URI.create(generated.baseUrl()).getPath();
        if (target instanceof Number port && port.intValue() > 0) {
            return K6Suite.join("http://localhost:" + port.intValue(), contextPath);
        }
        if (target instanceof URI || target instanceof String && !((String) target).isBlank()) {
            return target.toString();
        }
        return generated.baseUrl();
    }

    private static @Nullable Object target(Class<?> testClass, Object instance) {
        for (Class<?> c = testClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.isAnnotationPresent(K6Target.class) || hasAnnotationNamed(f, "LocalServerPort")) {
                    try {
                        f.setAccessible(true);
                        return f.get(instance);
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        throw new ExtensionConfigurationException("cannot read " + f, e);
                    }
                }
            }
        }
        return null;
    }

    private static boolean hasAnnotationNamed(Field f, String simpleName) {
        for (Annotation a : f.getAnnotations()) {
            if (a.annotationType().getSimpleName().equals(simpleName)) {
                return true;
            }
        }
        return false;
    }
}
