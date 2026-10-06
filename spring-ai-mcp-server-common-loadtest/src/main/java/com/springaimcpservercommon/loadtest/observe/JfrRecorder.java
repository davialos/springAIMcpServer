package com.springaimcpservercommon.loadtest.observe;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Records the target's JVM with Java Flight Recorder while the load runs ({@code jcmd JFR.start} before, {@code
 * JFR.dump} + {@code JFR.stop} after) and, when the repository's analyzer is at hand
 * ({@code scripts/jfr-analyze.sh}, module {@code jfr-analyzer}), turns the recording into the CPU/allocation/GC/lock
 * hot-spot report. The JVM must run on the machine where the load test runs (or {@code jcmd} must be reachable with a
 * command prefix such as {@code docker exec app jcmd}; then the recording stays where the JVM wrote it).
 */
public final class JfrRecorder {

    /**
     * What to record.
     *
     * @param pid         the JVM's process id, or {@code null} to find it
     * @param match       regex on the {@code jcmd -l} line to pick the JVM (main class, jar, arguments)
     * @param jcmd        the jcmd command, split on blanks ({@code jcmd}, {@code sudo -u orders jcmd})
     * @param jfrSettings {@code profile} (more detail), {@code default}, or a {@code .jfc} path on the JVM's side
     * @param outDir      where the recording and the analysis go
     * @param packages    package prefixes of the code to attribute costs to (empty: every frame)
     */
    public record Settings(@Nullable String pid, @Nullable String match, String jcmd, String jfrSettings, Path outDir,
                           List<String> packages) {

        /** Compact constructor: defensive copy. */
        public Settings {
            packages = List.copyOf(packages);
        }
    }

    private final Settings settings;
    private final String pid;
    private final String name;
    private final Consumer<String> log;
    private final String remoteFile;

    private JfrRecorder(Settings settings, String pid, String name, Consumer<String> log) {
        this.settings = settings;
        this.pid = pid;
        this.name = name;
        this.log = log;
        this.remoteFile = Path.of(System.getProperty("java.io.tmpdir"), name + ".jfr").toString();
    }

    /**
     * Finds the JVM and starts a recording.
     *
     * @param settings what to record
     * @param testId   run id, used for the recording's name
     * @param log      progress lines
     * @return the running recording
     * @throws IllegalArgumentException when no or several JVMs match
     * @throws IllegalStateException    when jcmd refuses to start the recording
     */
    public static JfrRecorder start(Settings settings, String testId, Consumer<String> log) {
        String pid = settings.pid() != null ? settings.pid() : findPid(settings);
        String name = "loadtest-" + testId.replaceAll("[^A-Za-z0-9_-]", "-");
        JfrRecorder r = new JfrRecorder(settings, pid, name, log);
        String out = r.jcmd("JFR.start", "name=" + name, "settings=" + settings.jfrSettings(),
                "filename=" + r.remoteFile);
        if (!out.contains("Started recording")) {
            throw new IllegalStateException("jcmd could not start a recording in JVM " + pid + ": " + out.strip());
        }
        log.accept("jfr: recording JVM " + pid + " (" + settings.jfrSettings() + " settings)");
        return r;
    }

    /**
     * Dumps and stops the recording, copies it into the output directory and analyzes it if possible.
     *
     * @return the recording's path in the output directory (or where the JVM wrote it), if it could be dumped
     */
    public Optional<Path> stop() {
        String dump = jcmd("JFR.dump", "name=" + name, "filename=" + remoteFile);
        jcmd("JFR.stop", "name=" + name);
        if (!dump.contains("Dumped recording") && !Files.exists(Path.of(remoteFile))) {
            log.accept("jfr: dumping the recording failed: " + dump.strip());
            return Optional.empty();
        }
        Path target = settings.outDir().resolve(name + ".jfr");
        try {
            Files.createDirectories(settings.outDir());
            Files.move(Path.of(remoteFile), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.accept("jfr: recording stays at " + remoteFile + " (" + e.getMessage() + ")");
            target = Path.of(remoteFile);
        }
        log.accept("jfr: recording saved to " + target);
        analyze(target);
        return Optional.of(target);
    }

    private void analyze(Path recording) {
        Optional<Path> script = analyzerScript();
        if (script.isEmpty()) {
            log.accept("jfr: analyze it with scripts/jfr-analyze.sh " + recording + " -p <your.package>"
                    + " (docs/tools/jfr-analyzer.md); the analyzer script was not found from here");
            return;
        }
        List<String> cmd = new ArrayList<>(List.of("bash", script.get().toString(), recording.toString(), "-o",
                settings.outDir().toString()));
        for (String p : settings.packages()) {
            cmd.add("-p");
            cmd.add(p);
        }
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(5, TimeUnit.MINUTES)) {
                p.destroyForcibly();
            }
            output.lines().filter(l -> !l.isBlank()).forEach(l -> log.accept("jfr: " + l));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** {@code -Dloadtest.jfr.analyze} / {@code LOADTEST_JFR_ANALYZE}, else {@code scripts/jfr-analyze.sh} of the repository this runs from. */
    private static Optional<Path> analyzerScript() {
        String env = System.getProperty("loadtest.jfr.analyze", System.getenv("LOADTEST_JFR_ANALYZE"));
        if (env != null && Files.isRegularFile(Path.of(env))) {
            return Optional.of(Path.of(env));
        }
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path script = dir.resolve("scripts/jfr-analyze.sh");
            if (Files.isRegularFile(script)) {
                return Optional.of(script);
            }
        }
        return Optional.empty();
    }

    // ── jcmd ───────────────────────────────────────────────────────────────────────────────────────────

    private String jcmd(String... args) {
        List<String> cmd = new ArrayList<>(Arrays.asList(settings.jcmd().trim().split("\\s+")));
        if (!args[0].equals("-l")) {
            cmd.add(pid);
        }
        cmd.addAll(Arrays.asList(args));
        return run(cmd);
    }

    private static String run(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("cannot run " + cmd.getFirst() + " (a JDK is needed on the machine of the JVM): "
                    + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    /**
     * The one JVM that matches, from {@code jcmd -l}.
     *
     * @param settings the match and the jcmd command
     * @return its process id
     * @throws IllegalArgumentException when none or several match
     */
    public static String findPid(Settings settings) {
        List<String> cmd = new ArrayList<>(Arrays.asList(settings.jcmd().trim().split("\\s+")));
        cmd.add("-l");
        List<String> lines = run(cmd).lines()
                .filter(l -> l.matches("^\\d+ .*") && !l.contains("JCmd") && !l.contains("jdk.jcmd")
                        && !l.contains("com.springaimcpservercommon.loadtest"))
                .toList();
        Pattern match = settings.match() == null ? null : Pattern.compile(settings.match());
        List<String> candidates = match == null ? lines : lines.stream().filter(l -> match.matcher(l).find()).toList();
        if (candidates.size() != 1) {
            throw new IllegalArgumentException(candidates.isEmpty()
                    ? "no JVM found (jcmd -l); give --jvm-pid or --jvm-match" + (lines.isEmpty() ? "" : ", seen: " + lines)
                    : "several JVMs match, narrow --jvm-match or give --jvm-pid: " + candidates);
        }
        return candidates.getFirst().split(" ")[0];
    }
}
