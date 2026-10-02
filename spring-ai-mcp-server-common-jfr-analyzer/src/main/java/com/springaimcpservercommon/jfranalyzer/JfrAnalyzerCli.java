package com.springaimcpservercommon.jfranalyzer;

import com.springaimcpservercommon.jfranalyzer.collect.Format;
import com.springaimcpservercommon.jfranalyzer.model.AnalysisReport;
import com.springaimcpservercommon.jfranalyzer.model.Finding;
import com.springaimcpservercommon.jfranalyzer.report.ExcelReportWriter;
import com.springaimcpservercommon.jfranalyzer.report.HtmlReportWriter;
import com.springaimcpservercommon.jfranalyzer.report.JsonReportWriter;
import com.springaimcpservercommon.jfranalyzer.report.SummaryJsonWriter;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Command line: {@code jfr-analyze <recording.jfr> -p com.acme [-p ...] [options]}. Writes
 * {@code <name>-report.html} and {@code <name>-report.json} and prints the findings.
 */
public final class JfrAnalyzerCli {

    static final String USAGE = """
            Usage: jfr-analyze <recording.jfr> [options]

            Analyzes a Java Flight Recorder file and attributes CPU time, allocation, lock contention, parking, I/O
            and exceptions to methods (and lines) in your packages. Writes:
              <name>.html          interactive report with stacks and charts (engineers)
              <name>.json          the complete analysis, machine-readable
              <name>.xlsx          Excel dashboard and filterable sheets (teams, leadership)
              <name>-summary.json  shareable summary: status, metrics, findings, top hot spots; no stacks,
                                   thread names, paths, endpoints or command lines

            Options:
              -p, --package <prefix>   package (or class) to attribute costs to; repeatable or comma-separated.
                                       Without it every frame counts as application code.
              -x, --exclude <prefix>   package never attributed to (e.g. generated proxies); repeatable.
              -o, --output <dir>       output directory (default: next to the recording)
              -n, --name <base>        report file name without extension (default: <recording>-report)
                  --format <list>      any of html,json,xlsx,summary (default: all four)
                  --top <n>            rows per ranked list (default 25)
                  --stacks <n>         call paths kept per hot spot (default 5)
                  --depth <n>          frames kept per call path (default 24)
                  --compact-json       do not indent the JSON
              -h, --help               this help

            Record with, for example:
              java -XX:StartFlightRecording:settings=profile,filename=app.jfr \\
                   -XX:FlightRecorderOptions:stackdepth=256 -jar app.jar
              jcmd <pid> JFR.start settings=profile duration=5m filename=app.jfr
            """;

    private JfrAnalyzerCli() {
    }

    /**
     * @param args command line
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * @param args command line
     * @param out  standard output
     * @param err  standard error
     * @return exit code: 0 ok, 1 analysis failed, 2 bad usage
     */
    static int run(String[] args, PrintStream out, PrintStream err) {
        AnalyzerOptions options;
        try {
            options = parse(args);
        } catch (HelpRequested help) {
            out.print(USAGE);
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            err.println();
            err.print(USAGE);
            return 2;
        }
        try {
            long started = System.nanoTime();
            AnalysisReport report = new JfrAnalyzer(options).analyze();
            Files.createDirectories(options.outputDirectory());
            List<Path> written = new ArrayList<>();
            if (options.writeHtml()) {
                Path html = options.outputDirectory().resolve(options.baseName() + ".html");
                Files.writeString(html, HtmlReportWriter.write(report), StandardCharsets.UTF_8);
                written.add(html);
            }
            if (options.writeJson()) {
                Path json = options.outputDirectory().resolve(options.baseName() + ".json");
                Files.writeString(json, JsonReportWriter.write(report, options.prettyJson()), StandardCharsets.UTF_8);
                written.add(json);
            }
            if (options.writeExcel()) {
                Path xlsx = options.outputDirectory().resolve(options.baseName() + ".xlsx");
                Files.write(xlsx, ExcelReportWriter.write(report));
                written.add(xlsx);
            }
            if (options.writeSummary()) {
                Path summary = options.outputDirectory().resolve(options.baseName() + "-summary.json");
                Files.writeString(summary, SummaryJsonWriter.write(report, options.prettyJson()),
                        StandardCharsets.UTF_8);
                written.add(summary);
            }
            printSummary(report, out);
            out.printf("%nAnalyzed in %s%n", Format.millis((System.nanoTime() - started) / 1e6));
            written.forEach(p -> out.println("Wrote " + p.toAbsolutePath().normalize()));
            return 0;
        } catch (IOException | RuntimeException e) {
            err.println("error: cannot analyze " + options.recording() + ": " + e);
            return 1;
        }
    }

    private static void printSummary(AnalysisReport report, PrintStream out) {
        var s = report.summary();
        out.printf("Recording %s, %s CPU samples, %s in packages %s%n", Format.millis(s.durationMillis()),
                Format.count(s.cpuSamples()), Format.percent(s.cpuPercentInPackages()),
                report.meta().packages().isEmpty() ? "(all)" : report.meta().packages());
        if (s.topCpuLocation() != null) {
            out.println("Hottest line:      " + s.topCpuLocation());
        }
        if (s.topAllocationLocation() != null) {
            out.println("Top allocation:    " + s.topAllocationLocation());
        }
        if (s.topBlockingLocation() != null) {
            out.println("Most blocked:      " + s.topBlockingLocation());
        }
        var exec = report.executiveSummary();
        out.printf("Status: %s, health score %d/100. %s%n", exec.status(), exec.healthScore(), exec.headline());
        out.printf("GC: %d collections, %s paused (%s), max pause %s%n", s.gcCount(), Format.millis(s.gcTotalPauseMs()),
                Format.percent(s.gcOverheadPercent()), Format.millis(s.gcMaxPauseMs()));
        List<Finding> findings = report.findings();
        out.printf("%nFindings (%d critical, %d warning):%n", s.criticalFindings(), s.warningFindings());
        for (Finding f : findings) {
            out.printf("  [%-8s] %s%n", f.severity(), f.title());
            if (f.location() != null) {
                out.println("             at " + f.location());
            }
        }
    }

    static AnalyzerOptions parse(String[] args) {
        Path recording = null;
        List<String> packages = new ArrayList<>();
        List<String> excludes = new ArrayList<>();
        Path output = null;
        String name = null;
        boolean html = true;
        boolean json = true;
        boolean xlsx = true;
        boolean summary = true;
        boolean pretty = true;
        int top = AnalyzerOptions.DEFAULT_TOP_N;
        int stacks = AnalyzerOptions.DEFAULT_STACKS_PER_HOTSPOT;
        int depth = AnalyzerOptions.DEFAULT_STACK_DEPTH;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            @Nullable String inline = null;
            int eq = arg.indexOf('=');
            if (arg.startsWith("--") && eq > 0) {
                inline = arg.substring(eq + 1);
                arg = arg.substring(0, eq);
            }
            switch (arg) {
                case "-h", "--help" -> throw new HelpRequested();
                case "-p", "--package", "--packages" -> packages.addAll(split(value(args, ++i, arg, inline)));
                case "-x", "--exclude" -> excludes.addAll(split(value(args, ++i, arg, inline)));
                case "-o", "--output" -> output = Path.of(value(args, ++i, arg, inline));
                case "-n", "--name" -> name = value(args, ++i, arg, inline);
                case "--format" -> {
                    List<String> formats = split(value(args, ++i, arg, inline));
                    List<String> unknown = formats.stream()
                            .filter(f -> !List.of("html", "json", "xlsx", "summary").contains(f)).toList();
                    if (formats.isEmpty() || !unknown.isEmpty()) {
                        throw new IllegalArgumentException("--format takes html, json, xlsx and/or summary"
                                + (unknown.isEmpty() ? "" : "; unknown: " + String.join(", ", unknown)));
                    }
                    html = formats.contains("html");
                    json = formats.contains("json");
                    xlsx = formats.contains("xlsx");
                    summary = formats.contains("summary");
                }
                case "--top" -> top = number(value(args, ++i, arg, inline), arg);
                case "--stacks" -> stacks = number(value(args, ++i, arg, inline), arg);
                case "--depth" -> depth = number(value(args, ++i, arg, inline), arg);
                case "--compact-json" -> pretty = false;
                default -> {
                    if (arg.startsWith("-")) {
                        throw new IllegalArgumentException("unknown option " + arg);
                    }
                    if (recording != null) {
                        throw new IllegalArgumentException("only one recording at a time (got " + recording + " and "
                                + arg + ")");
                    }
                    recording = Path.of(arg);
                }
            }
            if (inline != null && !List.of("-p", "--package", "--packages", "-x", "--exclude", "-o", "--output", "-n",
                    "--name", "--format", "--top", "--stacks", "--depth").contains(arg)) {
                throw new IllegalArgumentException(arg + " takes no value");
            }
            if (inline != null) {
                i--;
            }
        }
        if (recording == null) {
            throw new IllegalArgumentException("no recording given");
        }
        if (!Files.isRegularFile(recording)) {
            throw new IllegalArgumentException("not a file: " + recording);
        }
        AnalyzerOptions defaults = AnalyzerOptions.defaults(recording, packages);
        return new AnalyzerOptions(recording, packages, excludes,
                output == null ? defaults.outputDirectory() : output, name == null ? defaults.baseName() : name,
                html, json, xlsx, summary, pretty, top, stacks, depth);
    }

    private static String value(String[] args, int i, String option, @Nullable String inline) {
        if (inline != null) {
            return inline;
        }
        if (i >= args.length || args[i].startsWith("-")) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[i];
    }

    private static int number(String value, String option) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " needs a number, got " + value);
        }
    }

    private static List<String> split(String value) {
        return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static final class HelpRequested extends RuntimeException {
        private static final long serialVersionUID = 1L;

        HelpRequested() {
            super(null, null, false, false);
        }
    }
}
