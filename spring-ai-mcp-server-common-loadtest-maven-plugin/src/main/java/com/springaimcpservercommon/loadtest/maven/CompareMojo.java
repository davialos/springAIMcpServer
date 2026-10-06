package com.springaimcpservercommon.loadtest.maven;

import com.springaimcpservercommon.loadtest.api.CiReport;
import com.springaimcpservercommon.loadtest.api.LoadTestReport;
import com.springaimcpservercommon.loadtest.api.ReportComparison;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * {@code mvn loadtest:compare}: the regression gate. Compares the newest report of a mode (or {@code current})
 * with a baseline report, API by API, writes {@code reports/comparison-<mode>.md} and fails on a regression.
 * With {@code updateBaseline}, a passing run becomes the new baseline.
 */
@Mojo(name = "compare", requiresProject = false, threadSafe = true)
public class CompareMojo extends AbstractMojo {

    /** Suite directory. */
    @Parameter(defaultValue = "${project.basedir}/load-tests", property = "loadtest.suite")
    protected @Nullable File suite;

    /** Baseline report file. With {@code ci}, a missing file is the first run: nothing to compare with yet. */
    @Parameter(property = "loadtest.baseline", required = true)
    protected @Nullable File baseline;

    /** Load mode whose newest report is judged when there is no baseline yet. */
    @Parameter(defaultValue = "smoke", property = "loadtest.mode")
    protected String mode = "smoke";

    /** CI outputs: {@code reports/pr-comment.md}, the job summary ({@code $GITHUB_STEP_SUMMARY}) and the trend history. */
    @Parameter(defaultValue = "false", property = "loadtest.ci")
    protected boolean ci;

    /** The trend history ({@code .jsonl}) the run is added to; default {@code <suite>/history/trend.jsonl} with {@code ci}. */
    @Parameter(property = "loadtest.history")
    protected @Nullable File history;

    /** Report to judge (default: the newest report of the baseline's mode). */
    @Parameter(property = "loadtest.current")
    protected @Nullable File current;

    /** Allowed p95 increase in percent. */
    @Parameter(defaultValue = "20", property = "loadtest.maxP95Increase")
    protected double maxP95Increase = 20;

    /** p95 increases below this many ms are noise. */
    @Parameter(defaultValue = "10", property = "loadtest.minP95DeltaMs")
    protected double minP95DeltaMs = 10;

    /** Allowed increase of the failed-request share (0.01 = one percentage point). */
    @Parameter(defaultValue = "0.01", property = "loadtest.maxFailedIncrease")
    protected double maxFailedIncrease = 0.01;

    /** APIs with fewer requests in either run are not judged. */
    @Parameter(defaultValue = "10", property = "loadtest.minRequests")
    protected long minRequests = 10;

    /** Fail the build on a regression. */
    @Parameter(defaultValue = "true", property = "loadtest.failOnRegression")
    protected boolean failOnRegression = true;

    /** Copy the current report over the baseline when nothing regressed. */
    @Parameter(defaultValue = "false", property = "loadtest.updateBaseline")
    protected boolean updateBaseline;

    /** Creates the goal (instantiated by Maven). */
    public CompareMojo() {
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        boolean firstRun = ci && baseline != null && !baseline.isFile();
        if (baseline == null || !baseline.isFile() && !firstRun) {
            throw new MojoFailureException("baseline report not found: " + baseline);
        }
        Path dir = (suite == null ? new File("load-tests") : suite).toPath();
        LoadTestReport base;
        LoadTestReport now;
        try {
            base = firstRun ? null : LoadTestReport.read(baseline.toPath());
            String judged = base == null ? mode : base.mode();
            now = current != null ? LoadTestReport.read(current.toPath())
                    : LoadTestReport.latest(dir, judged).orElseThrow(() -> new IllegalArgumentException(
                    "no " + judged + " report in " + dir.resolve("reports")));
        } catch (IllegalArgumentException e) {
            throw new MojoFailureException(e.getMessage(), e);
        }
        ReportComparison c = base == null ? null : ReportComparison.compare(base, now,
                new ReportComparison.Rules(maxP95Increase, minP95DeltaMs, maxFailedIncrease, minRequests));
        if (c != null) {
            RunMojo.writeComparison(dir, c);
            c.toMarkdown().lines().forEach(getLog()::info);
        } else {
            getLog().info("loadtest: no baseline at " + baseline + " yet - nothing to compare with");
        }
        if (ci || history != null) {
            Path trend = history != null ? history.toPath() : dir.resolve("history/trend.jsonl");
            CiReport.Published p = CiReport.publish(dir, now, c, trend, System.getenv());
            getLog().info("loadtest: CI report " + p.comment() + (p.summary() ? " (also in the job summary)" : ""));
        }
        if (c != null && !c.passed()) {
            String message = "load test regression: " + c.regressions().stream()
                    .map(ch -> ch.api() + " (" + ch.reason() + ")").toList();
            if (failOnRegression) {
                throw new MojoFailureException(message);
            }
            getLog().warn(message);
            return;
        }
        if (updateBaseline && !now.file().toAbsolutePath().equals(baseline.toPath().toAbsolutePath())) {
            try {
                if (baseline.toPath().toAbsolutePath().getParent() != null) {
                    Files.createDirectories(baseline.toPath().toAbsolutePath().getParent());
                }
                Files.copy(now.file(), baseline.toPath(), StandardCopyOption.REPLACE_EXISTING);
                getLog().info("loadtest: baseline updated from " + now.file().getFileName());
            } catch (IOException e) {
                throw new MojoExecutionException("cannot update the baseline", e);
            }
        }
    }
}
