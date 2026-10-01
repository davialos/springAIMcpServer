package com.springaimcpservercommon.loadtest.discovery;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Locates a Spring project's files for single- and multi-module Maven/Gradle builds: main Java sources (including
 * code generated into {@code target/generated-sources} / {@code build/generated}, e.g. OpenAPI-generator API
 * interfaces), Spring configuration files, bundled OpenAPI specs and build files.
 */
final class ProjectFiles {

    private static final Set<String> SKIPPED_DIRS = Set.of(
            "target", "build", "out", "bin", ".git", ".gradle", ".idea", ".mvn", "node_modules", "offline-repo");
    private static final Pattern OPENAPI_HEAD = Pattern.compile("(?m)^\\s*\"?(openapi|swagger)\"?\\s*:");
    private static final Set<String> SPEC_DIRS = Set.of("api", "apis", "openapi", "spec", "specs", "contract",
            "contracts", "swagger");
    private static final long MAX_SPEC_BYTES = 5_000_000;

    private ProjectFiles() {
    }

    /**
     * Main (non-test) {@code .java} files: those under {@code src/main/java} plus generated main sources
     * ({@code target/generated-sources}, {@code build/generated}); when the project has no such directory, every
     * {@code .java} file outside test and build directories.
     *
     * @param projectDir project root
     * @return source files, sorted
     */
    static List<Path> javaSources(Path projectDir) {
        List<Path> all = walk(projectDir, ".java");
        List<Path> main = new ArrayList<>(all.stream()
                .filter(p -> p.toString().replace('\\', '/').contains("/src/main/java/")).toList());
        main.addAll(generatedSources(projectDir));
        List<Path> chosen = main.isEmpty()
                ? all.stream().filter(p -> !p.toString().replace('\\', '/').contains("/src/test/")).toList()
                : main;
        return chosen.stream().distinct().sorted().toList();
    }

    /** Java files generated for main code (API interfaces and models from OpenAPI generator, MapStruct …). */
    private static List<Path> generatedSources(Path projectDir) {
        List<Path> roots = new ArrayList<>();
        for (Path module : modules(projectDir)) {
            roots.add(module.resolve("target/generated-sources"));
            roots.add(module.resolve("build/generated"));
        }
        List<Path> out = new ArrayList<>();
        for (Path root : roots) {
            if (Files.isDirectory(root)) {
                for (Path p : walkAll(root, ".java")) {
                    String s = p.toString().replace('\\', '/');
                    if (!s.contains("/generated-test-sources/") && !s.contains("/test/")) {
                        out.add(p);
                    }
                }
            }
        }
        return out;
    }

    /** The project directory and every directory holding a build file below it (modules). */
    private static List<Path> modules(Path projectDir) {
        List<Path> out = new ArrayList<>();
        out.add(projectDir);
        for (Path build : buildFiles(projectDir)) {
            Path dir = build.getParent();
            if (dir != null && !out.contains(dir)) {
                out.add(dir);
            }
        }
        return out;
    }

    /**
     * Maven and Gradle build files of the project and its modules.
     *
     * @param projectDir project root
     * @return {@code pom.xml}, {@code build.gradle}, {@code build.gradle.kts} files
     */
    static List<Path> buildFiles(Path projectDir) {
        List<Path> out = new ArrayList<>();
        for (String name : List.of("pom.xml", "build.gradle", "build.gradle.kts")) {
            for (Path p : walk(projectDir, name)) {
                if (p.getFileName().toString().equals(name)) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    /**
     * Whether any build file declares a dependency whose coordinates contain the given text.
     *
     * @param projectDir project root
     * @param artifact   e.g. {@code spring-boot-starter-data-rest}
     * @return {@code true} if declared
     */
    static boolean declares(Path projectDir, String artifact) {
        for (Path p : buildFiles(projectDir)) {
            try {
                if (Files.readString(p).contains(artifact)) {
                    return true;
                }
            } catch (IOException e) {
                // unreadable build file: treat as not declaring it
            }
        }
        return false;
    }

    /**
     * Spring configuration files under {@code src/main/resources}: {@code application.properties},
     * {@code application.yml} and {@code application.yaml}, shallowest first (the root module wins).
     *
     * @param projectDir project root
     * @return configuration files
     */
    static List<Path> applicationConfigs(Path projectDir) {
        List<Path> out = new ArrayList<>();
        for (String ext : List.of(".properties", ".yml", ".yaml")) {
            for (Path p : walk(projectDir, ext)) {
                String s = p.toString().replace('\\', '/');
                if (s.contains("/src/main/resources/") && p.getFileName().toString().equals("application" + ext)) {
                    out.add(p);
                }
            }
        }
        out.sort((a, b) -> Integer.compare(a.getNameCount(), b.getNameCount()));
        return out;
    }

    /**
     * SQL scripts that define the database schema: Flyway migrations, Liquibase formatted-SQL changelogs and
     * Spring's {@code schema*.sql} init scripts under {@code src/main/resources}. Data-only scripts
     * ({@code data*.sql}) are left out.
     *
     * @param projectDir project root
     * @return schema scripts, unordered
     */
    static List<Path> schemaScripts(Path projectDir) {
        List<Path> out = new ArrayList<>();
        for (Path p : walk(projectDir, ".sql")) {
            String s = p.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
            String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (s.contains("/src/main/resources/") && !name.startsWith("data")
                    && (s.contains("/migration") || s.contains("/changelog") || s.contains("/changes/")
                    || s.contains("/flyway/") || s.contains("/liquibase/") || name.startsWith("schema"))) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * OpenAPI/Swagger documents bundled with the project (API-first projects generate their controllers from
     * them): YAML/JSON files under {@code src/main/resources} or a top-level {@code api}/{@code openapi}/
     * {@code spec}/{@code contracts} directory whose content starts like an OpenAPI document.
     *
     * @param projectDir project root
     * @return spec files, sorted
     */
    static List<Path> openApiSpecs(Path projectDir) {
        List<Path> out = new ArrayList<>();
        for (String ext : List.of(".yml", ".yaml", ".json")) {
            for (Path p : walk(projectDir, ext)) {
                String s = p.toString().replace('\\', '/');
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                Path rel = projectDir.relativize(p);
                boolean inSpecDir = rel.getNameCount() > 1 && SPEC_DIRS.contains(rel.getName(0).toString()
                        .toLowerCase(Locale.ROOT));
                if ((s.contains("/src/main/resources/") || inSpecDir) && !name.startsWith("application")
                        && looksLikeOpenApi(p)) {
                    out.add(p);
                }
            }
        }
        return out.stream().sorted().toList();
    }

    private static boolean looksLikeOpenApi(Path p) {
        try {
            if (Files.size(p) > MAX_SPEC_BYTES) {
                return false;
            }
            try (InputStream in = Files.newInputStream(p)) {
                String head = new String(in.readNBytes(4096), StandardCharsets.UTF_8);
                return OPENAPI_HEAD.matcher(head).find();
            }
        } catch (IOException e) {
            return false;
        }
    }

    private static List<Path> walk(Path root, String suffix) {
        return walk(root, suffix, true);
    }

    private static List<Path> walkAll(Path root, String suffix) {
        return walk(root, suffix, false);
    }

    private static List<Path> walk(Path root, String suffix, boolean skipBuildDirs) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path name = dir.getFileName();
                    return skipBuildDirs && !dir.equals(root) && name != null
                            && SKIPPED_DIRS.contains(name.toString())
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName().toString().endsWith(suffix)) {
                        out.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
