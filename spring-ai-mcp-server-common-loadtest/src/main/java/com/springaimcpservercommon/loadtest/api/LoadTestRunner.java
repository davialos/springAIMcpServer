package com.springaimcpservercommon.loadtest.api;

import com.springaimcpservercommon.loadtest.k6.K6Runner;
import com.springaimcpservercommon.loadtest.k6.LoadMode;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs a generated suite with k6 and returns its outcome and report.
 * <pre>{@code
 * RunResult r = LoadTestRunner.suite(Path.of("load-tests"))
 *         .mode("mixed-load").env("VUS", "20")
 *         .grafana("http://localhost:9090/api/v1/write")
 *         .run();
 * r.report().ifPresent(rep -> System.out.println(rep.total().p95Ms()));
 * }</pre>
 * The k6 executable comes from {@link #k6(String)}, {@code K6_BIN}, or {@code k6} on the {@code PATH}.
 */
public final class LoadTestRunner {

    /** k6's exit code when thresholds failed. */
    public static final int THRESHOLDS_FAILED = 99;

    private final Path suite;
    private String mode = "smoke";
    private @Nullable String dataMode;
    private final Map<String, String> env = new LinkedHashMap<>();
    private final Map<String, String> processEnv = new LinkedHashMap<>();
    private final List<String> extra = new ArrayList<>();
    private @Nullable String k6;
    private Consumer<String> output = System.out::println;
    private @Nullable Duration timeout;

    private LoadTestRunner(Path suite) {
        this.suite = suite;
    }

    /**
     * Starts a run of a generated suite.
     *
     * @param suiteDir directory holding {@code main.js}
     * @return a runner
     */
    public static LoadTestRunner suite(Path suiteDir) {
        return new LoadTestRunner(suiteDir);
    }

    /**
     * The outcome of a run.
     *
     * @param exitCode k6's exit code: 0 passed, {@value #THRESHOLDS_FAILED} thresholds failed
     * @param output   everything k6 printed (stdout and stderr)
     * @param report   the report the run wrote, if it got that far
     * @param testId   the {@code testid} tag of the run's metrics (Grafana filter)
     */
    public record RunResult(int exitCode, String output, Optional<LoadTestReport> report, String testId) {

        /**
         * Whether k6 exited cleanly (all thresholds passed).
         *
         * @return {@code true} on exit code 0
         */
        public boolean passed() {
            return exitCode == 0;
        }

        /**
         * Whether the run completed but thresholds failed.
         *
         * @return {@code true} on exit code {@value #THRESHOLDS_FAILED}
         */
        public boolean thresholdsFailed() {
            return exitCode == THRESHOLDS_FAILED;
        }
    }

    /**
     * Load mode: {@code smoke}, {@code load}, {@code stress}, {@code spike}, {@code soak}, {@code breakpoint}, each
     * also as {@code mixed-…} and {@code journey-…}, or {@code preview}.
     *
     * @param mode mode (default {@code smoke})
     * @return this
     */
    public LoadTestRunner mode(String mode) {
        if (!LoadMode.modeNames().contains(mode)) {
            throw new IllegalArgumentException("mode must be one of " + String.join(", ", LoadMode.modeNames()));
        }
        this.mode = mode;
        return this;
    }

    /**
     * Data mode for this run ({@code auto}, {@code dummy}, {@code random}, {@code real}, {@code user},
     * {@code mixed}); default: the suite config's.
     *
     * @param dataMode data mode
     * @return this
     */
    public LoadTestRunner dataMode(String dataMode) {
        this.dataMode = dataMode;
        return this;
    }

    /**
     * A suite variable ({@code API}, {@code VUS}, {@code RATE}, {@code DURATION_SCALE}, {@code BASE_URL},
     * {@code SEED_PER_TABLE}, {@code SEED_CLEANUP}, {@code READ_ONLY} …).
     *
     * @param name  variable
     * @param value value
     * @return this
     */
    public LoadTestRunner env(String name, String value) {
        this.env.put(name, value);
        return this;
    }

    /**
     * Streams metrics to Prometheus (k6's remote-write output) so the suite's Grafana dashboard shows the run;
     * see {@code grafana/} in the suite. Grafana annotations are added when {@code GRAFANA_URL} is set.
     *
     * @param remoteWriteUrl e.g. {@code http://localhost:9090/api/v1/write}
     * @return this
     */
    public LoadTestRunner grafana(String remoteWriteUrl) {
        extra.add("--out");
        extra.add("experimental-prometheus-rw");
        processEnv.put("K6_PROMETHEUS_RW_SERVER_URL", remoteWriteUrl);
        processEnv.put("K6_PROMETHEUS_RW_TREND_STATS", "p(95),p(99),avg,max");
        return this;
    }

    /**
     * An extra k6 argument (e.g. {@code --out}, {@code json=results.json}).
     *
     * @param arg argument
     * @return this
     */
    public LoadTestRunner k6Arg(String arg) {
        this.extra.add(arg);
        return this;
    }

    /**
     * The k6 executable.
     *
     * @param path path to k6
     * @return this
     */
    public LoadTestRunner k6(String path) {
        this.k6 = path;
        return this;
    }

    /**
     * Receives k6's output line by line (default: standard output).
     *
     * @param output consumer
     * @return this
     */
    public LoadTestRunner output(Consumer<String> output) {
        this.output = output;
        return this;
    }

    /**
     * Stops k6 after this long (it is killed and the result has exit code 124).
     *
     * @param timeout limit; {@code null} for none
     * @return this
     */
    public LoadTestRunner timeout(@Nullable Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    /**
     * The k6 executable this runner would use, if it can be found.
     *
     * @param explicit an explicit path, or {@code null}
     * @return the executable path, if it exists
     */
    public static Optional<String> findK6(@Nullable String explicit) {
        if (explicit != null) {
            return Files.isExecutable(Path.of(explicit)) ? Optional.of(explicit) : Optional.empty();
        }
        String env = System.getenv("K6_BIN");
        if (env != null && !env.isBlank()) {
            return Files.isExecutable(Path.of(env)) ? Optional.of(env) : Optional.empty();
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                for (String name : List.of("k6", "k6.exe")) {
                    Path p = Path.of(dir, name);
                    if (Files.isExecutable(p)) {
                        return Optional.of(p.toString());
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Runs k6 in the suite directory and waits for it.
     *
     * @return exit code, output and report
     * @throws IllegalArgumentException when the suite has no {@code main.js}
     * @throws UncheckedIOException when k6 cannot be started
     */
    public RunResult run() {
        if (!Files.exists(suite.resolve("main.js"))) {
            throw new IllegalArgumentException(suite + " has no main.js (generate the suite first)");
        }
        String testId = mode + "-" + java.time.Instant.now().toString().replaceAll("[:.]", "-");
        List<String> args = new ArrayList<>(extra);
        args.add("--tag");
        args.add("testid=" + testId);
        List<String> cmd = K6Runner.command(new K6Runner.Run(suite, mode, dataMode, env, k6, args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(suite.toFile()).redirectErrorStream(true);
        pb.environment().putAll(processEnv);
        pb.environment().putIfAbsent("TEST_ID", testId);
        long started = System.currentTimeMillis();
        StringBuilder all = new StringBuilder();
        int exit;
        try {
            Process p = pb.start();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(),
                        StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (all) {
                            all.append(line).append('\n');
                        }
                        output.accept(line);
                    }
                } catch (IOException e) {
                    // process ended
                }
            });
            if (timeout != null && !p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly().waitFor();
                exit = 124;
            } else {
                exit = p.waitFor();
            }
            reader.join(Duration.ofSeconds(10));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot start k6 (install it, set K6_BIN or call k6(path)): "
                    + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exit = 130;
        }
        Optional<LoadTestReport> report = LoadTestReport.reports(suite, mode).stream()
                .filter(f -> f.toFile().lastModified() >= started - 1000)
                .findFirst().map(LoadTestReport::read);
        String text;
        synchronized (all) {
            text = all.toString();
        }
        return new RunResult(exit, text, report, testId);
    }
}
