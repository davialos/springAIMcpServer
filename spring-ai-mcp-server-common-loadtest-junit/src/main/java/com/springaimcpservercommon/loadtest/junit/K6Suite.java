package com.springaimcpservercommon.loadtest.junit;

import com.springaimcpservercommon.loadtest.api.LoadTestGenerator;
import com.springaimcpservercommon.loadtest.api.LoadTestReport;
import com.springaimcpservercommon.loadtest.api.LoadTestRunner;
import com.springaimcpservercommon.loadtest.api.ReportComparison;
import org.jspecify.annotations.Nullable;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A generated suite, injected into the test methods of a {@link K6LoadTest} class. Runs go against the test's
 * application (the base URL is resolved at run time, after Spring injected the port).
 */
public final class K6Suite {

    private final LoadTestGenerator.GenerationResult generation;
    private final Supplier<String> baseUrl;
    private final Map<String, String> env;
    private final boolean requireK6;

    K6Suite(LoadTestGenerator.GenerationResult generation, Supplier<String> baseUrl, Map<String, String> env,
            boolean requireK6) {
        this.generation = generation;
        this.baseUrl = baseUrl;
        this.env = Map.copyOf(env);
        this.requireK6 = requireK6;
    }

    /**
     * The suite directory.
     *
     * @return directory holding {@code main.js}
     */
    public Path dir() {
        return generation.outDir();
    }

    /**
     * What was generated (APIs, fields, seeding order).
     *
     * @return the generation result
     */
    public LoadTestGenerator.GenerationResult generation() {
        return generation;
    }

    /**
     * The target base URL runs use.
     *
     * @return base URL with context path
     */
    public String baseUrl() {
        return baseUrl.get();
    }

    /**
     * A copy of this suite with one more variable for every run ({@code API}, {@code VUS} …).
     *
     * @param name  variable
     * @param value value
     * @return a new suite handle
     */
    public K6Suite withEnv(String name, String value) {
        Map<String, String> more = new LinkedHashMap<>(env);
        more.put(name, value);
        return new K6Suite(generation, baseUrl, more, requireK6);
    }

    /**
     * A runner for a mode, pointed at the test's application, k6 output going to standard output. Skips the test
     * when no k6 executable exists (unless {@link K6LoadTest#requireK6()}).
     *
     * @param mode load mode ({@code smoke}, {@code mixed-load}, {@code journey-smoke} …)
     * @return a configured runner; add options and call {@link LoadTestRunner#run()}
     */
    public LoadTestRunner runner(String mode) {
        if (LoadTestRunner.findK6(null).isEmpty()) {
            String message = "no k6 executable (install k6 or set K6_BIN)";
            if (requireK6) {
                throw new AssertionFailedError(message);
            }
            throw new TestAbortedException(message + ": load test skipped");
        }
        LoadTestRunner r = LoadTestRunner.suite(dir()).mode(mode).env("BASE_URL", baseUrl());
        env.forEach(r::env);
        return r;
    }

    /**
     * Runs a mode.
     *
     * @param mode load mode
     * @return the outcome
     */
    public LoadTestRunner.RunResult run(String mode) {
        return runner(mode).run();
    }

    /**
     * Runs a mode and fails the test unless k6 passed (every threshold met).
     *
     * @param mode load mode
     * @return the run's report
     * @throws AssertionFailedError naming the failed thresholds and the slowest/failing APIs
     */
    public LoadTestReport assertPassed(String mode) {
        LoadTestRunner.RunResult r = run(mode);
        if (!r.passed() || r.report().isEmpty()) {
            throw new AssertionFailedError(describe(mode, r));
        }
        return r.report().get();
    }

    /**
     * Runs a mode and fails the test when it regressed against a baseline report (and when thresholds failed).
     *
     * @param mode     load mode
     * @param baseline baseline report file
     * @param rules    regression rules ({@link ReportComparison.Rules#DEFAULTS})
     * @return the comparison
     */
    public ReportComparison assertNoRegression(String mode, Path baseline, ReportComparison.Rules rules) {
        LoadTestReport report = assertPassed(mode);
        ReportComparison c = ReportComparison.compare(LoadTestReport.read(baseline), report, rules);
        if (!c.passed()) {
            throw new AssertionFailedError("load test regression in " + mode + ":\n" + c.toMarkdown());
        }
        return c;
    }

    private static String describe(String mode, LoadTestRunner.RunResult r) {
        StringBuilder sb = new StringBuilder("load test " + mode + " failed (k6 exit " + r.exitCode() + ")");
        r.report().ifPresent(rep -> {
            if (!rep.failedThresholds().isEmpty()) {
                sb.append("\nfailed thresholds:");
                rep.failedThresholds().forEach(t -> sb.append("\n  ").append(t));
            }
            for (LoadTestReport.ApiStats a : rep.apis()) {
                if (a.failedRate() != null && a.failedRate() > 0) {
                    sb.append(String.format(Locale.ROOT, "%n  %s %s: %.1f%% failed of %d", a.api(), a.name(),
                            a.failedRate() * 100, a.requests()));
                }
            }
        });
        List<String> lines = r.output().lines().toList();
        sb.append("\nk6 output (last lines):");
        lines.subList(Math.max(0, lines.size() - 25), lines.size()).forEach(l -> sb.append("\n  ").append(l));
        return sb.toString();
    }

    static @Nullable String join(@Nullable String base, @Nullable String path) {
        if (base == null) {
            return null;
        }
        String p = path == null ? "" : path.replaceAll("/+$", "");
        return base.replaceAll("/+$", "") + (p.isEmpty() || p.startsWith("/") ? p : "/" + p);
    }
}
