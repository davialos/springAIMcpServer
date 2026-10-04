package com.springaimcpservercommon.loadtest.maven;

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

    /** Baseline report file. */
    @Parameter(property = "loadtest.baseline", required = true)
    protected @Nullable File baseline;

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
        if (baseline == null || !baseline.isFile()) {
            throw new MojoFailureException("baseline report not found: " + baseline);
        }
        Path dir = (suite == null ? new File("load-tests") : suite).toPath();
        LoadTestReport base;
        LoadTestReport now;
        try {
            base = LoadTestReport.read(baseline.toPath());
            now = current != null ? LoadTestReport.read(current.toPath())
                    : LoadTestReport.latest(dir, base.mode()).orElseThrow(() -> new IllegalArgumentException(
                    "no " + base.mode() + " report in " + dir.resolve("reports")));
        } catch (IllegalArgumentException e) {
            throw new MojoFailureException(e.getMessage(), e);
        }
        ReportComparison c = ReportComparison.compare(base, now,
                new ReportComparison.Rules(maxP95Increase, minP95DeltaMs, maxFailedIncrease, minRequests));
        RunMojo.writeComparison(dir, c);
        c.toMarkdown().lines().forEach(getLog()::info);
        if (!c.passed()) {
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
                Files.copy(now.file(), baseline.toPath(), StandardCopyOption.REPLACE_EXISTING);
                getLog().info("loadtest: baseline updated from " + now.file().getFileName());
            } catch (IOException e) {
                throw new MojoExecutionException("cannot update the baseline", e);
            }
        }
    }
}
