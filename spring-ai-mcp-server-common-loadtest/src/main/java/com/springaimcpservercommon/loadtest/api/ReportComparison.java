package com.springaimcpservercommon.loadtest.api;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Compares a run with a baseline run of the same suite, API by API: the regression gate for CI ("fail the build
 * when an API got more than 20% slower or started failing").
 *
 * @param baseline the reference run
 * @param current  the run under test
 * @param rules    what counts as a regression
 * @param changes  one row per API present in either run, in the current run's order
 */
public record ReportComparison(LoadTestReport baseline, LoadTestReport current, Rules rules, List<Change> changes) {

    /** Compact constructor: defensive copy. */
    public ReportComparison {
        changes = List.copyOf(changes);
    }

    /**
     * What counts as a regression.
     *
     * @param maxP95IncreasePct   allowed p95 increase in percent
     * @param minP95IncreaseMs    p95 increases smaller than this are noise and never a regression
     * @param maxFailedRateIncrease allowed increase of the failed-request share (0.01 = one percentage point)
     * @param minRequests         APIs with fewer requests in either run are too noisy to judge
     */
    public record Rules(double maxP95IncreasePct, double minP95IncreaseMs, double maxFailedRateIncrease,
                        long minRequests) {

        /** Defaults: +20% p95 (ignoring changes under 10 ms), +1 percentage point errors, at least 10 requests. */
        public static final Rules DEFAULTS = new Rules(20, 10, 0.01, 10);
    }

    /**
     * One API compared.
     *
     * @param api              API id
     * @param baselineP95Ms    p95 in the baseline, or {@code null}
     * @param currentP95Ms     p95 now, or {@code null}
     * @param baselineFailed   failed share in the baseline, or {@code null}
     * @param currentFailed    failed share now, or {@code null}
     * @param status           {@code ok}, {@code regression}, {@code improved}, {@code new}, {@code missing} or
     *                         {@code too-few-requests}
     * @param reason           why it is a regression or improvement, else empty
     */
    public record Change(String api, @Nullable Double baselineP95Ms, @Nullable Double currentP95Ms,
                         @Nullable Double baselineFailed, @Nullable Double currentFailed, String status,
                         String reason) {

        /**
         * Whether this API regressed.
         *
         * @return {@code true} for a regression
         */
        public boolean regression() {
            return status.equals("regression");
        }
    }

    /**
     * Compares two reports.
     *
     * @param baseline reference run
     * @param current  run under test
     * @param rules    regression rules
     * @return the comparison
     */
    public static ReportComparison compare(LoadTestReport baseline, LoadTestReport current, Rules rules) {
        Set<String> apis = new LinkedHashSet<>();
        current.apis().forEach(a -> apis.add(a.api()));
        baseline.apis().forEach(a -> apis.add(a.api()));
        List<Change> changes = new ArrayList<>();
        for (String api : apis) {
            LoadTestReport.ApiStats b = baseline.api(api).orElse(null);
            LoadTestReport.ApiStats c = current.api(api).orElse(null);
            if (b == null || b.requests() == 0) {
                changes.add(new Change(api, null, c == null ? null : c.p95Ms(), null,
                        c == null ? null : c.failedRate(), "new", ""));
                continue;
            }
            if (c == null || c.requests() == 0) {
                changes.add(new Change(api, b.p95Ms(), null, b.failedRate(), null, "missing", ""));
                continue;
            }
            if (b.requests() < rules.minRequests() || c.requests() < rules.minRequests()) {
                changes.add(new Change(api, b.p95Ms(), c.p95Ms(), b.failedRate(), c.failedRate(),
                        "too-few-requests", ""));
                continue;
            }
            List<String> worse = new ArrayList<>();
            List<String> better = new ArrayList<>();
            if (b.p95Ms() != null && c.p95Ms() != null && b.p95Ms() > 0) {
                double delta = c.p95Ms() - b.p95Ms();
                double pct = delta / b.p95Ms() * 100;
                if (delta >= rules.minP95IncreaseMs() && pct > rules.maxP95IncreasePct()) {
                    worse.add(String.format(Locale.ROOT, "p95 %.1f → %.1f ms (+%.0f%%)", b.p95Ms(), c.p95Ms(), pct));
                } else if (-delta >= rules.minP95IncreaseMs() && -pct > rules.maxP95IncreasePct()) {
                    better.add(String.format(Locale.ROOT, "p95 %.1f → %.1f ms (%.0f%%)", b.p95Ms(), c.p95Ms(), pct));
                }
            }
            double bf = b.failedRate() == null ? 0 : b.failedRate();
            double cf = c.failedRate() == null ? 0 : c.failedRate();
            if (cf - bf > rules.maxFailedRateIncrease()) {
                worse.add(String.format(Locale.ROOT, "failed %.2f%% → %.2f%%", bf * 100, cf * 100));
            } else if (bf - cf > rules.maxFailedRateIncrease()) {
                better.add(String.format(Locale.ROOT, "failed %.2f%% → %.2f%%", bf * 100, cf * 100));
            }
            String status = !worse.isEmpty() ? "regression" : !better.isEmpty() ? "improved" : "ok";
            changes.add(new Change(api, b.p95Ms(), c.p95Ms(), b.failedRate(), c.failedRate(), status,
                    String.join("; ", !worse.isEmpty() ? worse : better)));
        }
        return new ReportComparison(baseline, current, rules, changes);
    }

    /**
     * The regressed APIs.
     *
     * @return regressions, empty when the run is as good as the baseline
     */
    public List<Change> regressions() {
        return changes.stream().filter(Change::regression).toList();
    }

    /**
     * Whether the run passes the gate: no API regressed.
     *
     * @return {@code true} without regressions
     */
    public boolean passed() {
        return regressions().isEmpty();
    }

    /**
     * Markdown table of every API (for CI job summaries and PR comments).
     *
     * @return Markdown
     */
    public String toMarkdown() {
        StringBuilder md = new StringBuilder();
        md.append("# Load test comparison — ").append(current.mode()).append("\n\n");
        md.append("- Baseline: `").append(baseline.file().getFileName()).append("`\n");
        md.append("- Current: `").append(current.file().getFileName()).append("`\n");
        md.append(String.format(Locale.ROOT, "- Rules: p95 +%.0f%% (ignoring < %.0f ms), failed +%.2f pp, "
                        + "≥ %d requests\n\n", rules.maxP95IncreasePct(), rules.minP95IncreaseMs(),
                rules.maxFailedRateIncrease() * 100, rules.minRequests()));
        md.append("| API | p95 before | p95 now | failed before | failed now | status | detail |\n");
        md.append("|---|---:|---:|---:|---:|---|---|\n");
        for (Change c : changes) {
            md.append("| ").append(c.api()).append(" | ").append(ms(c.baselineP95Ms())).append(" | ")
                    .append(ms(c.currentP95Ms())).append(" | ").append(pct(c.baselineFailed())).append(" | ")
                    .append(pct(c.currentFailed())).append(" | ")
                    .append(c.regression() ? "**regression**" : c.status()).append(" | ").append(c.reason())
                    .append(" |\n");
        }
        md.append('\n').append(passed() ? "No regression." : regressions().size() + " API(s) regressed.")
                .append('\n');
        return md.toString();
    }

    private static String ms(@Nullable Double v) {
        return v == null ? "-" : String.format(Locale.ROOT, "%.1f", v);
    }

    private static String pct(@Nullable Double v) {
        return v == null ? "-" : String.format(Locale.ROOT, "%.2f%%", v * 100);
    }
}
