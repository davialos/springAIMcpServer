package com.springaimcpservercommon.loadtest.discovery;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The few Spring Boot settings of the target project that shape its HTTP surface and its database, read from
 * {@code application.properties}/{@code application.yml} (default profile only). {@code ${VAR:default}}
 * placeholders resolve against the environment, then their default.
 *
 * @param serverPort         {@code server.port}, default 8080
 * @param contextPath        {@code server.servlet.context-path} + {@code spring.mvc.servlet.path}, or {@code null}
 * @param snakeCaseJson      {@code spring.jackson.property-naming-strategy} is {@code SNAKE_CASE}
 * @param datasourceUrl      {@code spring.datasource.url}
 * @param datasourceUsername {@code spring.datasource.username}
 * @param datasourcePassword {@code spring.datasource.password}
 * @param unwrapRootValue    {@code spring.jackson.deserialization.unwrap-root-value}: request bodies are wrapped in
 *                           an object named after the class ({@code @JsonRootName})
 * @param dataRestBasePath   {@code spring.data.rest.base-path} (Spring Data REST), or {@code null}
 * @param properties         every flattened property of the default profile, raw (for placeholders)
 */
public record ProjectSettings(int serverPort, @Nullable String contextPath, boolean snakeCaseJson,
                              @Nullable String datasourceUrl, @Nullable String datasourceUsername,
                              @Nullable String datasourcePassword, boolean unwrapRootValue,
                              @Nullable String dataRestBasePath, Map<String, String> properties) {

    /** Compact constructor: unmodifiable copy. */
    public ProjectSettings {
        properties = Map.copyOf(properties);
    }

    /**
     * Resolves {@code ${key:default}} placeholders (as in {@code @RequestMapping("${api.base:/api}")}) against the
     * project's properties, then the environment, then the default.
     *
     * @param value text with placeholders
     * @return resolved text; unresolvable placeholders without default are left as they are
     */
    public String resolvePlaceholders(String value) {
        Matcher m = PLACEHOLDER.matcher(value);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String v = properties.get(key);
            if (v == null) {
                v = System.getenv(key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
            }
            if (v == null) {
                v = m.group(2) != null ? m.group(2) : m.group(0);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(v));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^:}]+)(?::([^}]*))?}");

    /** Settings of a project with no configuration file. */
    public static final ProjectSettings DEFAULTS = new ProjectSettings(8080, null, false, null, null, null, false,
            null, Map.of());

    /**
     * Reads the settings of a project.
     *
     * @param projectDir project root
     * @return settings, {@link #DEFAULTS} when no configuration file exists
     */
    public static ProjectSettings read(Path projectDir) {
        return read(projectDir, System::getenv);
    }

    /**
     * Reads the settings of a project, resolving placeholders with the given environment.
     *
     * @param projectDir project root
     * @param env        environment lookup
     * @return settings
     */
    static ProjectSettings read(Path projectDir, UnaryOperator<@Nullable String> env) {
        Map<String, String> flat = new HashMap<>();
        // Shallowest file wins: read deepest first, let shallower files overwrite.
        var files = ProjectFiles.applicationConfigs(projectDir).reversed();
        for (Path file : files) {
            flat.putAll(file.toString().endsWith(".properties") ? properties(file) : yaml(file));
        }
        if (flat.isEmpty()) {
            return DEFAULTS;
        }
        UnaryOperator<@Nullable String> get = key -> resolve(flat.get(key), env);
        String port = get.apply("server.port");
        String context = join(get.apply("server.servlet.context-path"), get.apply("spring.mvc.servlet.path"));
        String naming = get.apply("spring.jackson.property-naming-strategy");
        String unwrap = firstOf(flat, env, "spring.jackson.deserialization.unwrap-root-value",
                "spring.jackson.deserialization.UNWRAP_ROOT_VALUE");
        String dataRest = firstOf(flat, env, "spring.data.rest.base-path", "spring.data.rest.basePath");
        return new ProjectSettings(
                parsePort(port),
                context,
                naming != null && naming.toUpperCase(Locale.ROOT).replace("_", "").contains("SNAKECASE"),
                get.apply("spring.datasource.url"),
                get.apply("spring.datasource.username"),
                get.apply("spring.datasource.password"),
                "true".equalsIgnoreCase(unwrap),
                dataRest == null || dataRest.isBlank() ? null : join(dataRest, null),
                flat);
    }

    /**
     * The URL the project listens on locally.
     *
     * @return e.g. {@code http://localhost:8080/shop}
     */
    public String localBaseUrl() {
        return "http://localhost:" + serverPort + (contextPath == null ? "" : contextPath);
    }

    private static @Nullable String firstOf(Map<String, String> flat, UnaryOperator<@Nullable String> env,
                                            String... keys) {
        for (String k : keys) {
            String v = resolve(flat.get(k), env);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static int parsePort(@Nullable String port) {
        try {
            return port == null ? 8080 : Integer.parseInt(port.trim());
        } catch (NumberFormatException e) {
            return 8080;
        }
    }

    private static @Nullable String join(@Nullable String a, @Nullable String b) {
        String joined = ((a == null ? "" : a) + "/" + (b == null ? "" : b)).replaceAll("/+", "/");
        joined = joined.endsWith("/") ? joined.substring(0, joined.length() - 1) : joined;
        return joined.isEmpty() ? null : joined;
    }

    static @Nullable String resolve(@Nullable String value, UnaryOperator<@Nullable String> env) {
        if (value == null) {
            return null;
        }
        Matcher m = PLACEHOLDER.matcher(value);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String fromEnv = env.apply(m.group(1));
            if (fromEnv == null) {
                // Spring's relaxed binding: spring.datasource.url ↔ SPRING_DATASOURCE_URL
                fromEnv = env.apply(m.group(1).toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
            }
            String replacement = fromEnv != null ? fromEnv : m.group(2);
            if (replacement == null) {
                return null;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static Map<String, String> properties(Path file) {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file)) {
            p.load(r);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> out = new HashMap<>();
        p.stringPropertyNames().forEach(k -> out.put(k, p.getProperty(k)));
        return out;
    }

    private static Map<String, String> yaml(Path file) {
        Map<String, String> out = new HashMap<>();
        try {
            JsonNode root = YAMLMapper.builder().build().readTree(file.toFile());
            flatten("", root, out);
        } catch (RuntimeException e) {
            // A config file we cannot read must not stop discovery; defaults apply.
        }
        return out;
    }

    private static void flatten(String prefix, @Nullable JsonNode node, Map<String, String> out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isObject()) {
            for (var e : node.properties()) {
                flatten(prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), e.getValue(), out);
            }
        } else if (!node.isArray()) {
            out.put(prefix, node.asString());
        }
    }
}
