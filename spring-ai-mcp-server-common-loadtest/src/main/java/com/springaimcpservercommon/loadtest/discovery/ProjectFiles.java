package com.springaimcpservercommon.loadtest.discovery;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Locates a project's main Java sources and resource files, for single- and multi-module builds.
 */
final class ProjectFiles {

    private static final Set<String> SKIPPED_DIRS = Set.of(
            "target", "build", "out", "bin", ".git", ".gradle", ".idea", ".mvn", "node_modules", "offline-repo");

    private ProjectFiles() {
    }

    /**
     * Main (non-test) {@code .java} files: those under a {@code src/main/java} directory, or, when the project
     * has no such directory, every {@code .java} file outside test and build directories.
     *
     * @param projectDir project root
     * @return source files, sorted
     */
    static List<Path> javaSources(Path projectDir) {
        List<Path> all = walk(projectDir, ".java");
        List<Path> main = all.stream().filter(p -> p.toString().replace('\\', '/').contains("/src/main/java/")).toList();
        List<Path> chosen = main.isEmpty()
                ? all.stream().filter(p -> !p.toString().replace('\\', '/').contains("/src/test/")).toList()
                : main;
        return chosen.stream().sorted().toList();
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

    private static List<Path> walk(Path root, String suffix) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path name = dir.getFileName();
                    return !dir.equals(root) && name != null && SKIPPED_DIRS.contains(name.toString())
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
