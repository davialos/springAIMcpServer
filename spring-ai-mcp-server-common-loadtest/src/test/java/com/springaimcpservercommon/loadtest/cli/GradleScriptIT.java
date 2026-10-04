package com.springaimcpservercommon.loadtest.cli;

import com.springaimcpservercommon.loadtest.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * {@code gradle/loadtest.gradle} in a real Gradle build: {@code loadtestDiscover} and {@code loadtestGenerate} in a
 * forked Java 25 toolchain JVM. Needs {@code GRADLE_BIN} (a Gradle executable); {@code GRADLE_JAVA_HOME} selects
 * the JDK that runs Gradle when the current one is newer than that Gradle supports. The generator classpath is
 * handed over through {@code loadtest { classpath = files(...) }}, so Maven local is not needed.
 */
class GradleScriptIT {

    @Test
    void generatesTheSuiteFromAGradleBuild(@TempDir Path dir) throws Exception {
        String gradle = System.getenv("GRADLE_BIN");
        assumeThat(gradle).as("GRADLE_BIN (a Gradle executable)").isNotBlank();
        Path project = dir.resolve("crm");
        copy(Fixtures.sampleCrm().resolve("src"), project.resolve("src"));
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'crm'\n");
        String classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(e -> !e.contains("junit") && !e.contains("assertj") && !e.contains("opentest4j")
                        && !e.contains("testcontainers") && !e.contains("test-classes"))
                .map(e -> "'" + e.replace("\\", "/") + "'").reduce((a, b) -> a + ", " + b).orElseThrow();
        Files.writeString(project.resolve("build.gradle"), """
                plugins { id 'java' }
                apply from: 'gradle/loadtest.gradle'
                loadtest {
                    classpath = files(%s)
                    generateArgs = ['--no-db', '--exclude', '/rest/**']
                }
                """.formatted(classpath));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(new LoadTestCli(new PrintStream(out), new PrintStream(out), InputStream.nullInputStream())
                .execute(new String[]{"init-gradle", "--project", project.toString()})).isZero();

        List<String> cmd = new ArrayList<>(List.of(gradle, "--no-daemon", "--offline", "-q",
                "-Porg.gradle.java.installations.auto-download=false",
                "-Porg.gradle.java.installations.paths=" + System.getProperty("java.home"),
                "loadtestDiscover", "loadtestGenerate"));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(project.toFile()).redirectErrorStream(true);
        String gradleJava = System.getenv("GRADLE_JAVA_HOME");
        if (gradleJava != null && !gradleJava.isBlank()) {
            pb.environment().put("JAVA_HOME", gradleJava);
        }
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(10, TimeUnit.MINUTES)).isTrue();
        assertThat(p.exitValue()).as(output).isZero();
        assertThat(output).contains("createDeal").contains("Seeding order");
        assertThat(project.resolve("load-tests/main.js")).exists();
        assertThat(project.resolve("load-tests/grafana/docker-compose.yml")).exists();
    }

    private static void copy(Path from, Path to) throws IOException {
        try (var files = Files.walk(from)) {
            for (Path f : files.toList()) {
                Path target = to.resolve(from.relativize(f).toString());
                if (Files.isDirectory(f)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(f, target);
                }
            }
        }
    }
}
