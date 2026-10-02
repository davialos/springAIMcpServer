package com.springaimcpservercommon.loadtest.maven;

import com.springaimcpservercommon.loadtest.api.LoadTestReport;
import com.springaimcpservercommon.loadtest.api.LoadTestRunner;
import com.springaimcpservercommon.loadtest.api.ReportComparison;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * {@code mvn loadtest:run}: runs the generated suite with k6 and fails the build when thresholds fail or, with a
 * {@code baseline}, when an API regressed. Bind it to {@code integration-test} between
 * {@code spring-boot:start} and {@code spring-boot:stop} to load-test every build.
 */
@Mojo(name = "run", requiresProject = false, threadSafe = true)
public class RunMojo extends AbstractMojo {

    /** Suite directory (written by {@code loadtest:generate}). */
    @Parameter(defaultValue = "${project.basedir}/load-tests", property = "loadtest.suite")
    protected @Nullable File suite;

    /** Load mode: smoke, load, stress, spike, soak, breakpoint, mixed-…, journey-…. */
    @Parameter(defaultValue = "smoke", property = "loadtest.mode")
    protected String mode = "smoke";

    /** Data mode for this run (default: the suite config's). */
    @Parameter(property = "loadtest.dataMode")
    protected @Nullable String dataMode;

    /** Target base URL for this run ({@code BASE_URL}). */
    @Parameter(property = "loadtest.baseUrl")
    protected @Nullable String baseUrl;

    /** Comma-separated API ids to run ({@code API}). */
    @Parameter(property = "loadtest.api")
    protected @Nullable String api;

    /** Further suite variables ({@code VUS}, {@code RATE}, {@code DURATION_SCALE}, {@code SEED_PER_TABLE} …). */
    @Parameter
    protected Map<String, String> env = Map.of();

    /** Extra k6 arguments. */
    @Parameter
    protected List<String> k6Args = List.of();

    /** k6 executable (default {@code K6_BIN} or {@code k6} on the PATH). */
    @Parameter(property = "loadtest.k6")
    protected @Nullable String k6;

    /** Stream metrics to the suite's Prometheus/Grafana stack and annotate the run. */
    @Parameter(defaultValue = "false", property = "loadtest.grafana")
    protected boolean grafana;

    /** Prometheus remote-write URL. */
    @Parameter(defaultValue = "http://localhost:9090/api/v1/write", property = "loadtest.prometheusUrl")
    protected String prometheusUrl = "http://localhost:9090/api/v1/write";

    /** Grafana URL for run annotations. */
    @Parameter(defaultValue = "http://localhost:3000", property = "loadtest.grafanaUrl")
    protected String grafanaUrl = "http://localhost:3000";

    /** Kill k6 after this many seconds (0 = no limit). */
    @Parameter(defaultValue = "0", property = "loadtest.timeoutSeconds")
    protected long timeoutSeconds;

    /** Fail the build when thresholds fail. */
    @Parameter(defaultValue = "true", property = "loadtest.failOnThresholds")
    protected boolean failOnThresholds = true;

    /** A baseline report to compare with ({@code reports/<mode>-….json} of an earlier run). */
    @Parameter(property = "loadtest.baseline")
    protected @Nullable File baseline;

    /** Allowed p95 increase in percent before an API counts as regressed. */
    @Parameter(defaultValue = "20", property = "loadtest.maxP95Increase")
    protected double maxP95Increase = 20;

    /** p95 increases below this many ms are noise. */
    @Parameter(defaultValue = "10", property = "loadtest.minP95DeltaMs")
    protected double minP95DeltaMs = 10;

    /** Allowed increase of the failed-request share (0.01 = one percentage point). */
    @Parameter(defaultValue = "0.01", property = "loadtest.maxFailedIncrease")
    protected double maxFailedIncrease = 0.01;

    /** Fail the build when an API regressed against the baseline. */
    @Parameter(defaultValue = "true", property = "loadtest.failOnRegression")
    protected boolean failOnRegression = true;

    /** Skip instead of failing when no k6 executable is found. */
    @Parameter(defaultValue = "false", property = "loadtest.skipIfK6Missing")
    protected boolean skipIfK6Missing;

    /** Skip the goal. */
    @Parameter(defaultValue = "false", property = "loadtest.skip")
    protected boolean skip;

    /** Creates the goal (instantiated by Maven). */
    public RunMojo() {
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("loadtest: skipped");
            return;
        }
        if (LoadTestRunner.findK6(k6).isEmpty()) {
            String message = "no k6 executable (install k6, set K6_BIN or <k6>)";
            if (skipIfK6Missing) {
                getLog().warn("loadtest: " + message + "; skipped");
                return;
            }
            throw new MojoExecutionException(message);
        }
        Path dir = (suite == null ? new File("load-tests") : suite).toPath();
        LoadTestRunner runner;
        try {
            runner = LoadTestRunner.suite(dir).mode(mode).output(getLog()::info);
        } catch (IllegalArgumentException e) {
            throw new MojoFailureException(e.getMessage(), e);
        }
        if (dataMode != null) {
            runner.dataMode(dataMode);
        }
        if (baseUrl != null) {
            runner.env("BASE_URL", baseUrl);
        }
        if (api != null) {
            runner.env("API", api);
        }
        env.forEach(runner::env);
        k6Args.forEach(runner::k6Arg);
        if (k6 != null) {
            runner.k6(k6);
        }
        if (grafana) {
            runner.grafana(prometheusUrl).grafanaAnnotations(grafanaUrl, System.getenv("GRAFANA_TOKEN"));
        }
        if (timeoutSeconds > 0) {
            runner.timeout(Duration.ofSeconds(timeoutSeconds));
        }
        LoadTestRunner.RunResult r;
        try {
            r = runner.run();
        } catch (IllegalArgumentException e) {
            throw new MojoFailureException(e.getMessage(), e);
        } catch (UncheckedIOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
        r.report().ifPresent(rep -> getLog().info("loadtest: report " + rep.file()));
        if (baseline != null && r.report().isPresent()) {
            ReportComparison c = ReportComparison.compare(LoadTestReport.read(baseline.toPath()), r.report().get(),
                    new ReportComparison.Rules(maxP95Increase, minP95DeltaMs, maxFailedIncrease,
                            ReportComparison.Rules.DEFAULTS.minRequests()));
            writeComparison(dir, c);
            c.toMarkdown().lines().forEach(getLog()::info);
            if (!c.passed() && failOnRegression) {
                throw new MojoFailureException("load test regression: " + c.regressions().stream()
                        .map(ch -> ch.api() + " (" + ch.reason() + ")").toList());
            }
        }
        if (r.passed()) {
            return;
        }
        String message = r.thresholdsFailed()
                ? "load test thresholds failed: " + r.report().map(LoadTestReport::failedThresholds).orElse(List.of())
                : "k6 exited with " + r.exitCode();
        if (r.thresholdsFailed() && !failOnThresholds) {
            getLog().warn("loadtest: " + message);
            return;
        }
        throw new MojoFailureException(message);
    }

    static void writeComparison(Path suiteDir, ReportComparison c) {
        try {
            Path file = suiteDir.resolve("reports/comparison-" + c.current().mode() + ".md");
            Files.createDirectories(file.getParent());
            Files.writeString(file, c.toMarkdown());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
