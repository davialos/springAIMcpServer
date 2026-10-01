package com.springaimcpservercommon.jfranalyzer.report;

import com.springaimcpservercommon.core.json.CanonicalJson;
import com.springaimcpservercommon.jfranalyzer.collect.Format;
import com.springaimcpservercommon.jfranalyzer.model.AnalysisReport;
import com.springaimcpservercommon.jfranalyzer.model.CpuReport;
import com.springaimcpservercommon.jfranalyzer.model.ExceptionReport;
import com.springaimcpservercommon.jfranalyzer.model.Finding;
import com.springaimcpservercommon.jfranalyzer.model.GcEvent;
import com.springaimcpservercommon.jfranalyzer.model.GcGroup;
import com.springaimcpservercommon.jfranalyzer.model.GcReport;
import com.springaimcpservercommon.jfranalyzer.model.HotLine;
import com.springaimcpservercommon.jfranalyzer.model.Hotspot;
import com.springaimcpservercommon.jfranalyzer.model.HotspotReport;
import com.springaimcpservercommon.jfranalyzer.model.IoReport;
import com.springaimcpservercommon.jfranalyzer.model.JvmInfo;
import com.springaimcpservercommon.jfranalyzer.model.MemoryReport;
import com.springaimcpservercommon.jfranalyzer.model.ReportMeta;
import com.springaimcpservercommon.jfranalyzer.model.Severity;
import com.springaimcpservercommon.jfranalyzer.model.StackFrameView;
import com.springaimcpservercommon.jfranalyzer.model.StackPath;
import com.springaimcpservercommon.jfranalyzer.model.Summary;
import com.springaimcpservercommon.jfranalyzer.model.ThreadReport;
import com.springaimcpservercommon.jfranalyzer.model.TimePoint;
import com.springaimcpservercommon.jfranalyzer.model.WeightedName;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Renders a report as one self-contained HTML page: no external scripts, styles or fonts, so it can be archived,
 * attached to a ticket or opened offline. Charts are drawn in the browser from data embedded in the page (rendered
 * by {@link CanonicalJson}); every table is plain HTML and readable without script.
 */
public final class HtmlReportWriter {

    private final StringBuilder out = new StringBuilder(256 * 1024);
    private final Map<String, Object> charts = new LinkedHashMap<>();
    private final AnalysisReport report;

    private HtmlReportWriter(AnalysisReport report) {
        this.report = report;
    }

    /**
     * @param report the report
     * @return the HTML page
     */
    public static String write(AnalysisReport report) {
        return new HtmlReportWriter(report).render();
    }

    private String render() {
        ReportMeta meta = report.meta();
        String file = meta.recordingFile().substring(meta.recordingFile().lastIndexOf('/') + 1);
        out.append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>JFR Analysis Report</title>\n<style>\n").append(resource("report.css"))
                .append("</style>\n</head>\n<body>\n");
        out.append("<header class=\"top\"><button class=\"theme-toggle\" type=\"button\">Toggle theme</button>")
                .append("<h1>JFR analysis: ").append(esc(file)).append("</h1><div class=\"sub\">")
                .append(esc(Format.millis(meta.durationMillis()))).append(" recorded · ")
                .append(esc(meta.recordingStart())).append(" → ").append(esc(meta.recordingEnd()))
                .append(" · analyzed ").append(esc(meta.analyzedAt())).append("</div><div class=\"chips\">");
        if (meta.packages().isEmpty()) {
            out.append("<span class=\"chip\">all packages</span>");
        }
        meta.packages().forEach(p -> out.append("<span class=\"chip\">").append(esc(p)).append("</span>"));
        meta.excludedPackages().forEach(p -> out.append("<span class=\"chip ex\" title=\"excluded\">")
                .append(esc(p)).append("</span>"));
        out.append("</div></header>\n");
        out.append("<nav class=\"toc\"><div>");
        for (String[] link : new String[][]{{"summary", "Summary"}, {"findings", "Findings"}, {"cpu", "CPU"},
                {"memory", "Memory"}, {"gc", "GC"}, {"threads", "Threads & locks"}, {"io", "I/O"},
                {"exceptions", "Exceptions"}, {"recording", "Recording"}}) {
            out.append("<a href=\"#").append(link[0]).append("\">").append(link[1]).append("</a>");
        }
        out.append("</div></nav>\n<main>\n");
        summary(report.summary(), report.memory());
        findings(report.findings());
        cpu(report.cpu());
        memory(report.memory());
        gc(report.gc());
        threads(report.threads());
        io(report.io());
        exceptions(report.exceptions());
        recording(meta);
        out.append("</main>\n<script type=\"application/json\" id=\"chart-data\">")
                .append(CanonicalJson.write(JsonReportWriter.tree(charts)).replace("</", "<\\/"))
                .append("</script>\n<script>\n").append(resource("report.js")).append("</script>\n</body>\n</html>\n");
        return out.toString();
    }

    // ---------------------------------------------------------------- sections

    private void summary(Summary s, MemoryReport memory) {
        open("summary", "Summary");
        out.append("<div class=\"tiles\">");
        tile("Recording", Format.millis(s.durationMillis()), Format.count(s.cpuSamples()) + " CPU samples");
        tile("CPU in your packages", Format.percent(s.cpuPercentInPackages()), s.topCpuLocation() == null ? null
                : "hottest: " + s.topCpuLocation());
        tile("JVM CPU (avg)", s.jvmCpuAvgPercent() == null ? "–" : Format.percent(s.jvmCpuAvgPercent()),
                "of all cores");
        tile("Heap peak / max", bytesOrDash(s.heapPeakUsedBytes()) + " / " + bytesOrDash(s.heapMaxBytes()),
                memory.liveSetLastBytes() == null ? null : "after last GC " + Format.bytes(memory.liveSetLastBytes()));
        tile("Allocation rate", s.allocationRateBytesPerSec() == null ? "–"
                : Format.bytes(s.allocationRateBytesPerSec()) + "/s", s.topAllocationLocation() == null ? null
                : "top: " + s.topAllocationLocation());
        tile("GC pauses", Format.millis(s.gcTotalPauseMs()), s.gcCount() + " collections · max "
                + Format.millis(s.gcMaxPauseMs()) + " · " + Format.percent(s.gcOverheadPercent()) + " overhead");
        tile("Blocked on locks", Format.millis(s.monitorBlockedMs()),
                "parked in your code " + Format.millis(s.parkedInPackagesMs())
                        + (s.topBlockingLocation() == null ? "" : " · most: " + s.topBlockingLocation()));
        tile("Exceptions created", s.exceptionsThrown() == null ? "–" : Format.count(s.exceptionsThrown()),
                s.pinnedEvents() > 0 ? s.pinnedEvents() + " virtual-thread pinning events" : null);
        tile("Findings", s.criticalFindings() + " critical · " + s.warningFindings() + " warning", null);
        out.append("</div>");
        close();
    }

    private void findings(List<Finding> findings) {
        open("findings", "Findings");
        out.append("<div class=\"card\">");
        if (findings.isEmpty()) {
            out.append("<p class=\"empty\">No findings: nothing crossed a threshold.</p>");
        }
        for (Finding f : findings) {
            String icon = switch (f.severity()) {
                case CRITICAL -> "✖";
                case WARNING -> "▲";
                case INFO -> "ℹ";
            };
            out.append("<div class=\"finding\"><div><span class=\"sev ").append(f.severity().name())
                    .append("\"><span class=\"ic\" aria-hidden=\"true\">").append(icon).append("</span>")
                    .append(label(f.severity())).append("</span><div class=\"cat\">").append(esc(f.category()))
                    .append("</div></div><div><div class=\"t\">").append(esc(f.title())).append("</div><div class=\"d\">")
                    .append(esc(f.detail())).append("</div>");
            if (f.location() != null) {
                out.append("<div>").append(location(f.location())).append("</div>");
            }
            out.append("</div></div>");
        }
        out.append("</div>");
        close();
    }

    private void cpu(CpuReport cpu) {
        open("cpu", "CPU hot spots");
        out.append("<p class=\"hint\">Each sample is attributed to the method in your packages closest to the top of "
                + "the stack: the line of your code that was running or that called the JDK/library code that was. "
                + "Expand a row for its hottest lines, where the time was actually spent and the call paths. "
                + "Samples from ").append(esc(cpu.sampleSource())).append(".</p>");
        out.append("<div class=\"grid2\">");
        List<Map<String, Object>> load = new ArrayList<>();
        if (!cpu.jvmCpuTimeline().isEmpty()) {
            load.add(series("JVM", "var(--s1)", "line", cpu.jvmCpuTimeline()));
            load.add(series("Machine", "var(--s2)", "line", cpu.machineCpuTimeline()));
        }
        chart("cpuLoad", "CPU load", "pct", load, List.of(), 100.0,
                "No jdk.CPULoad events in this recording.");
        List<Map<String, Object>> samples = new ArrayList<>();
        if (!cpu.sampleTimeline().isEmpty()) {
            samples.add(series("All threads", "var(--s1)", "area", cpu.sampleTimeline()));
            samples.add(series("Your packages", "var(--s2)", "line", cpu.packageSampleTimeline()));
        }
        chart("cpuSamples", "Execution samples per second", "persec", samples, List.of(), null,
                "No execution samples in this recording.");
        out.append("</div>");
        hotspots(cpu.execution(), "Record with -XX:StartFlightRecording:settings=profile to sample methods.");
        if (!cpu.threadStates().isEmpty()) {
            h3("Sampled thread states");
            names(cpu.threadStates(), "State", "samples");
        }
        hotspots(cpu.nativeSamples(), null);
        close();
    }

    private void memory(MemoryReport m) {
        open("memory", "Heap & allocation");
        out.append("<div class=\"tiles\">");
        tile("Max heap", bytesOrDash(m.heapMaxBytes()), m.heapInitialBytes() == null ? null
                : "initial " + Format.bytes(m.heapInitialBytes()));
        tile("Peak used", bytesOrDash(m.heapPeakUsedBytes()), m.heapPeakCommittedBytes() == null ? null
                : "peak committed " + Format.bytes(m.heapPeakCommittedBytes()));
        tile("Live set (after GC)", m.liveSetAvgBytes() == null ? "–" : Format.bytes(m.liveSetAvgBytes()) + " avg",
                m.liveSetMinBytes() == null ? null
                        : "min " + Format.bytes(m.liveSetMinBytes()) + " · max " + Format.bytes(m.liveSetMaxBytes()));
        tile("Live-set trend", m.liveSetGrowthBytesPerMin() == null ? "–"
                : Format.bytes(m.liveSetGrowthBytesPerMin()) + "/min", "least-squares slope of heap after GC");
        tile("Allocated", bytesOrDash(m.allocatedBytes()), m.allocationSource());
        tile("Allocation rate", m.allocationRateBytesPerSec() == null ? "–"
                : Format.bytes(m.allocationRateBytesPerSec()) + "/s", null);
        tile("Metaspace used (max)", bytesOrDash(m.metaspaceUsedMaxBytes()), m.metaspaceCommittedMaxBytes() == null
                ? null : "committed " + Format.bytes(m.metaspaceCommittedMaxBytes()));
        out.append("</div><div class=\"grid2\" style=\"margin-top:12px\">");
        List<Map<String, Object>> heap = new ArrayList<>();
        List<TimePoint> used = m.heapTimeline().stream().map(p -> new TimePoint(p.offsetMillis(), p.usedBytes()))
                .toList();
        List<TimePoint> after = m.heapTimeline().stream().filter(p -> p.when().startsWith("After"))
                .map(p -> new TimePoint(p.offsetMillis(), p.usedBytes())).toList();
        List<TimePoint> committed = m.heapTimeline().stream().filter(p -> p.committedBytes() > 0)
                .map(p -> new TimePoint(p.offsetMillis(), p.committedBytes())).toList();
        if (!used.isEmpty()) {
            heap.add(series("Used", "var(--s1)", "line", used));
            if (!after.isEmpty()) {
                heap.add(series("After GC", "var(--s2)", "dots", after));
            }
            if (!committed.isEmpty()) {
                heap.add(series("Committed", "var(--s3)", "line", committed));
            }
        }
        List<Map<String, Object>> refs = m.heapMaxBytes() == null || used.isEmpty() ? List.of()
                : List.of(Map.of("y", m.heapMaxBytes(), "label", "max heap " + Format.bytes(m.heapMaxBytes())));
        chart("heap", "Heap used", "bytes", heap, refs, null, "No heap summaries in this recording.");
        List<Map<String, Object>> rate = m.allocationRateTimeline().isEmpty() ? List.of()
                : List.of(series("Allocation rate", "var(--s1)", "area", m.allocationRateTimeline()));
        chart("allocRate", "Allocation rate (sampled)", "rate", rate, List.of(), null,
                "No allocation samples in this recording.");
        out.append("</div>");
        hotspots(m.allocations(), "jdk.ObjectAllocationSample is enabled in the default and profile settings.");
        hotspots(m.allocationsRequiringGc(), null);
        hotspots(m.oldObjects(), null);
        close();
    }

    private void gc(GcReport gc) {
        open("gc", "Garbage collection");
        out.append("<div class=\"tiles\">");
        tile("Collectors", (gc.youngCollector() == null ? "?" : gc.youngCollector()) + " / "
                + (gc.oldCollector() == null ? "?" : gc.oldCollector()), "young / old");
        tile("Collections", Format.count(gc.count()),
                String.format(Locale.ROOT, "%.1f per minute", gc.collectionsPerMinute()));
        tile("Total pause", Format.millis(gc.totalPauseMs()), Format.percent(gc.overheadPercent())
                + " of the recording (GC overhead)");
        tile("Max pause", Format.millis(gc.maxPauseMs()), "avg " + Format.millis(gc.avgPauseMs()));
        tile("Pause p50 / p95 / p99", Format.millis(gc.p50PauseMs()) + " / " + Format.millis(gc.p95PauseMs())
                + " / " + Format.millis(gc.p99PauseMs()), null);
        tile("GC time incl. concurrent", Format.millis(gc.totalDurationMs()), null);
        out.append("</div><div style=\"margin-top:12px\">");
        List<Map<String, Object>> pauses = gc.pauseTimeline().isEmpty() ? List.of()
                : List.of(series("Pause", "var(--s1)", "dots", gc.pauseTimeline()));
        chart("gcPauses", "GC pauses", "ms", pauses, List.of(), null, "No garbage collections in this recording.");
        out.append("</div>");
        if (gc.count() > 0) {
            out.append("<div class=\"grid2\"><div>");
            h3("By collector");
            gcGroups(gc.byCollector());
            out.append("</div><div>");
            h3("By cause");
            gcGroups(gc.byCause());
            out.append("</div></div>");
            h3("Longest pauses");
            out.append("<div class=\"card scroll\"><table><thead><tr><th class=\"num\">GC id</th><th>Collector</th>"
                    + "<th>Cause</th><th class=\"num\">At</th><th class=\"num\">Pause</th>"
                    + "<th class=\"num\">Duration</th><th class=\"num\">Heap before</th>"
                    + "<th class=\"num\">Heap after</th><th class=\"num\">Reclaimed</th></tr></thead><tbody>");
            for (GcEvent e : gc.longestPauses()) {
                out.append("<tr><td class=\"num\">").append(e.gcId()).append("</td><td>").append(esc(e.name()))
                        .append("</td><td>").append(esc(e.cause())).append("</td><td class=\"num\">")
                        .append(time(e.offsetMillis())).append("</td><td class=\"num\">")
                        .append(Format.millis(e.pauseMs())).append("</td><td class=\"num\">")
                        .append(Format.millis(e.durationMs())).append("</td><td class=\"num\">")
                        .append(bytesOrDash(e.heapBeforeBytes())).append("</td><td class=\"num\">")
                        .append(bytesOrDash(e.heapAfterBytes())).append("</td><td class=\"num\">")
                        .append(bytesOrDash(e.reclaimedBytes())).append("</td></tr>");
            }
            out.append("</tbody></table></div>");
        }
        close();
    }

    private void threads(ThreadReport t) {
        open("threads", "Threads, locks & blocking");
        out.append("<div class=\"tiles\">");
        tile("Peak threads", t.peakThreads() == null ? "–" : Format.count(t.peakThreads()),
                t.lastActiveThreads() == null ? null : t.lastActiveThreads() + " live at end, " + t.daemonThreads()
                        + " daemon");
        tile("Threads started", t.startedThreads() == null ? "–" : Format.count(t.startedThreads()), "since JVM start");
        tile("Blocked on monitors", Format.millis(t.monitorEnter().totalWeight() / 1e6),
                Format.count(t.monitorEnter().events()) + " contended entries");
        tile("Parked (in your code)", Format.millis(t.park().attributedWeight() / 1e6),
                Format.millis(t.park().totalWeight() / 1e6) + " in all threads");
        tile("Virtual-thread pinning", Format.count(t.pinned().events()), null);
        out.append("</div>");
        out.append("<p class=\"hint\">JFR records these only above a threshold (20 ms in the default settings, "
                + "10 ms in profile), so they show waits long enough to matter. Idle pool threads park all the time: "
                + "look at the hot spots in your packages, not the totals.</p>");
        hotspots(t.monitorEnter(), null);
        if (!t.lockOwners().isEmpty()) {
            h3("Threads holding the contended monitors");
            names(t.lockOwners(), "Owner thread", "nanos");
        }
        hotspots(t.park(), null);
        hotspots(t.monitorWait(), null);
        hotspots(t.sleep(), null);
        hotspots(t.pinned(), null);
        if (!t.blockedByThread().isEmpty()) {
            h3("Blocked time by thread (contended monitors + parks in your packages)");
            names(t.blockedByThread(), "Thread", "nanos");
        }
        close();
    }

    private void io(IoReport io) {
        open("io", "Blocking I/O");
        out.append("<div class=\"tiles\">");
        tile("Socket read", Format.millis(io.socketRead().totalWeight() / 1e6), Format.bytes(io.socketBytesRead()));
        tile("Socket write", Format.millis(io.socketWrite().totalWeight() / 1e6),
                Format.bytes(io.socketBytesWritten()));
        tile("File read", Format.millis(io.fileRead().totalWeight() / 1e6), Format.bytes(io.fileBytesRead()));
        tile("File write", Format.millis(io.fileWrite().totalWeight() / 1e6), Format.bytes(io.fileBytesWritten()));
        out.append("</div>");
        boolean any = false;
        for (HotspotReport r : List.of(io.socketRead(), io.socketWrite(), io.fileRead(), io.fileWrite())) {
            if (r.present()) {
                hotspots(r, null);
                any = true;
            }
        }
        if (!any) {
            out.append("<p class=\"empty\">No I/O event above JFR's threshold in this recording.</p>");
        }
        close();
    }

    private void exceptions(ExceptionReport e) {
        open("exceptions", "Exceptions");
        out.append("<div class=\"tiles\">");
        tile("Throwables created", e.throwablesCreated() == null ? "–" : Format.count(e.throwablesCreated()),
                e.throwablesPerSecond() == null ? null
                        : String.format(Locale.ROOT, "%.1f per second", e.throwablesPerSecond()));
        out.append("</div>");
        hotspots(e.throwSites(), "jdk.JavaExceptionThrow is off in the stock settings; enable it "
                + "(jdk.JavaExceptionThrow#enabled=true) to see where exceptions are thrown.");
        close();
    }

    private void recording(ReportMeta meta) {
        open("recording", "Recording");
        JvmInfo jvm = meta.jvm();
        out.append("<div class=\"card\"><dl class=\"kv\">");
        kv("File", meta.recordingFile() + " (" + Format.bytes(meta.fileSizeBytes()) + ")");
        kv("JVM", join(jvm.jvmName(), jvm.jvmVersion()));
        kv("PID", jvm.pid() == null ? null : String.valueOf(jvm.pid()));
        kv("OS", jvm.os());
        kv("CPU", join(jvm.cpu(), jvm.hardwareThreads() == null ? null : jvm.hardwareThreads() + " hardware threads"));
        kv("Physical memory", jvm.physicalMemoryBytes() == null ? null : Format.bytes(jvm.physicalMemoryBytes()));
        kv("JVM arguments", jvm.jvmArguments());
        kv("Application", jvm.javaArguments());
        kv("Packages", meta.packages().isEmpty() ? "(all)" : String.join(", ", meta.packages()));
        if (!meta.excludedPackages().isEmpty()) {
            kv("Excluded", String.join(", ", meta.excludedPackages()));
        }
        out.append("</dl></div>");
        h3("Events in the recording");
        out.append("<div class=\"card scroll\"><table><thead><tr><th>Event type</th><th class=\"num\">Count</th>"
                + "</tr></thead><tbody>");
        meta.eventCounts().entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(en -> out.append("<tr><td class=\"mono\">").append(esc(en.getKey()))
                        .append("</td><td class=\"num\">").append(Format.count(en.getValue())).append("</td></tr>"));
        out.append("</tbody></table></div>");
        close();
    }

    // ---------------------------------------------------------------- hot spots

    private void hotspots(HotspotReport r, @Nullable String hintWhenEmpty) {
        Function<Double, String> fmt = formatter(r.unit());
        h3(r.title());
        if (!r.present()) {
            out.append("<p class=\"empty\">No events of this kind in the recording.");
            if (hintWhenEmpty != null) {
                out.append(" ").append(esc(hintWhenEmpty));
            }
            out.append("</p>");
            return;
        }
        out.append("<p class=\"hint\">").append(Format.count(r.events())).append(" events, ")
                .append(esc(fmt.apply(r.totalWeight()))).append(" in total; ")
                .append(Format.percent(r.attributedPercent())).append(" (")
                .append(esc(fmt.apply(r.attributedWeight()))).append(") reached through your packages.");
        if (r.truncatedStacks() > 0) {
            out.append(" ").append(Format.count(r.truncatedStacks())).append(" stacks were truncated by JFR.");
        }
        out.append("</p><div class=\"card\">");
        if (r.hotspots().isEmpty()) {
            out.append("<p class=\"empty\">No stack in this section contains a frame from your packages.</p>");
        } else {
            out.append("<div class=\"hs-head\"><span>#</span><span>Method in your packages → hottest line</span>"
                    + "<span class=\"num\">").append(esc(unitLabel(r.unit()))).append("</span><span class=\"num\">"
                    + "Share</span><span class=\"hide-sm\"></span></div>");
            for (Hotspot h : r.hotspots()) {
                hotspot(h, r, fmt);
            }
        }
        out.append("</div>");
        out.append("<div class=\"cols\" style=\"margin-top:12px\">");
        if (!r.hotLines().isEmpty()) {
            out.append("<div>");
            h4("Hottest lines in your packages");
            lines(r.hotLines(), fmt);
            out.append("</div>");
        }
        if (!r.inclusive().isEmpty()) {
            out.append("<div>");
            h4("Your methods anywhere on the stack (inclusive)");
            names(r.inclusive(), "Method", r.unit());
            out.append("</div>");
        }
        if (!r.outside().isEmpty()) {
            out.append("<div>");
            h4("Not reached through your packages (leaf frame)");
            names(r.outside(), "Leaf frame", r.unit());
            out.append("</div>");
        }
        if (!r.detailLabel().isEmpty() && !r.topDetails().isEmpty()) {
            out.append("<div>");
            h4(r.detailLabel() + " (all events)");
            names(r.topDetails(), r.detailLabel(), r.unit());
            out.append("</div>");
        }
        if (!r.topThreads().isEmpty()) {
            out.append("<div>");
            h4("Threads (all events)");
            names(r.topThreads(), "Thread", r.unit());
            out.append("</div>");
        }
        out.append("</div>");
    }

    private void hotspot(Hotspot h, HotspotReport r, Function<Double, String> fmt) {
        out.append("<details class=\"hs\"><summary><span class=\"rank\">").append(h.rank())
                .append("</span><span><div class=\"method\">").append(esc(h.method())).append("</div><div>")
                .append(location(h.location())).append("</div></span><span class=\"num\">")
                .append(esc(fmt.apply(h.weight()))).append("</span><span class=\"num\">")
                .append(Format.percent(h.percent())).append("</span><span class=\"hide-sm\">").append(meter(h.percent()))
                .append("</span></summary><div class=\"hs-body\">");
        out.append("<div class=\"hint\">").append(Format.count(h.count())).append(" events · self (this method was "
                + "the top frame): ").append(Format.percent(h.weight() <= 0 ? 0 : h.selfWeight() * 100 / h.weight()))
                .append("</div><div class=\"cols\">");
        out.append("<div>");
        h4("Lines");
        lines(h.lines(), fmt);
        out.append("</div><div>");
        h4("Where the cost was paid (top frame)");
        names(h.callees(), "Frame", r.unit());
        out.append("</div>");
        if (!h.details().isEmpty()) {
            out.append("<div>");
            h4(r.detailLabel());
            names(h.details(), r.detailLabel(), r.unit());
            out.append("</div>");
        }
        if (!h.threads().isEmpty()) {
            out.append("<div>");
            h4("Threads");
            names(h.threads(), "Thread", r.unit());
            out.append("</div>");
        }
        out.append("</div>");
        if (!h.stacks().isEmpty()) {
            h4("Call paths (top frame first, your frames highlighted)");
            for (StackPath p : h.stacks()) {
                out.append("<div class=\"stack-head\">").append(esc(fmt.apply(p.weight()))).append(" · ")
                        .append(Format.percent(p.percent())).append(" · ").append(Format.count(p.count()))
                        .append(" events</div><ol class=\"stack\">");
                for (StackFrameView f : p.frames()) {
                    out.append("<li").append(f.inPackage() ? " class=\"app\"" : "").append(">")
                            .append(esc(f.location())).append("</li>");
                }
                if (p.truncated()) {
                    out.append("<li>…</li>");
                }
                out.append("</ol>");
            }
        }
        out.append("</div></details>");
    }

    private void lines(List<HotLine> lines, Function<Double, String> fmt) {
        out.append("<div class=\"scroll\"><table><thead><tr><th>Line</th><th class=\"num\">Weight</th>"
                + "<th class=\"num\">Share</th></tr></thead><tbody>");
        for (HotLine l : lines) {
            out.append("<tr><td>").append(location(l.location())).append("</td><td class=\"num\">")
                    .append(esc(fmt.apply(l.weight()))).append("</td><td class=\"num\">")
                    .append(Format.percent(l.percent())).append("</td></tr>");
        }
        out.append("</tbody></table></div>");
    }

    private void names(List<WeightedName> names, String header, String unit) {
        Function<Double, String> fmt = formatter(unit);
        out.append("<div class=\"scroll\"><table><thead><tr><th>").append(esc(header))
                .append("</th><th class=\"num\">").append(esc(unitLabel(unit)))
                .append("</th><th class=\"num\">Share</th><th class=\"num\">Events</th></tr></thead><tbody>");
        for (WeightedName n : names) {
            out.append("<tr><td class=\"mono\">").append(esc(n.name())).append("</td><td class=\"num\">")
                    .append(esc(fmt.apply(n.weight()))).append("</td><td class=\"num\">")
                    .append(Format.percent(n.percent())).append("</td><td class=\"num\">")
                    .append(Format.count(n.count())).append("</td></tr>");
        }
        out.append("</tbody></table></div>");
    }

    private void gcGroups(List<GcGroup> groups) {
        out.append("<div class=\"card scroll\"><table><thead><tr><th>Name</th><th class=\"num\">Count</th>"
                + "<th class=\"num\">Total pause</th><th class=\"num\">Max</th><th class=\"num\">Avg</th>"
                + "<th class=\"num\">Duration</th></tr></thead><tbody>");
        for (GcGroup g : groups) {
            out.append("<tr><td>").append(esc(g.name())).append("</td><td class=\"num\">").append(g.count())
                    .append("</td><td class=\"num\">").append(Format.millis(g.totalPauseMs()))
                    .append("</td><td class=\"num\">").append(Format.millis(g.maxPauseMs()))
                    .append("</td><td class=\"num\">").append(Format.millis(g.avgPauseMs()))
                    .append("</td><td class=\"num\">").append(Format.millis(g.totalDurationMs())).append("</td></tr>");
        }
        out.append("</tbody></table></div>");
    }

    // ---------------------------------------------------------------- charts

    private void chart(String id, String title, String unit, List<Map<String, Object>> series,
                       List<Map<String, Object>> refs, @Nullable Double fixedMax, String emptyText) {
        out.append("<div class=\"card\"><div class=\"chart-title\">").append(esc(title)).append("</div>");
        if (series.isEmpty()) {
            out.append("<p class=\"empty\">").append(esc(emptyText)).append("</p></div>");
            return;
        }
        out.append("<div class=\"chart\" data-chart=\"").append(id).append("\">");
        if (series.size() > 1) {
            out.append("<div class=\"legend\">");
            for (Map<String, Object> s : series) {
                out.append("<span><span class=\"key").append("dots".equals(s.get("kind")) ? " dot" : "")
                        .append("\" style=\"background:").append(s.get("color")).append("\"></span>")
                        .append(esc(String.valueOf(s.get("name")))).append("</span>");
            }
            out.append("</div>");
        }
        out.append("<div class=\"plot\"></div><div class=\"tip\" role=\"status\"></div></div></div>");
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("title", title);
        cfg.put("unit", unit);
        cfg.put("series", series);
        cfg.put("refs", refs);
        cfg.put("xMax", report.meta().durationMillis());
        if (fixedMax != null) {
            cfg.put("yFixedMax", fixedMax);
        }
        charts.put(id, cfg);
    }

    private static Map<String, Object> series(String name, String color, String kind, List<TimePoint> points) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", name);
        s.put("color", color);
        s.put("kind", kind);
        s.put("points", points.stream().map(p -> List.<Object>of(p.offsetMillis(), p.value())).toList());
        return s;
    }

    // ---------------------------------------------------------------- small pieces

    private void open(String id, String title) {
        out.append("<section class=\"block\" id=\"").append(id).append("\"><h2>").append(esc(title)).append("</h2>");
    }

    private void close() {
        out.append("</section>\n");
    }

    private void h3(String text) {
        out.append("<h3>").append(esc(text)).append("</h3>");
    }

    private void h4(String text) {
        out.append("<h4>").append(esc(text)).append("</h4>");
    }

    private void tile(String label, String value, @Nullable String note) {
        out.append("<div class=\"tile\"><div class=\"label\">").append(esc(label)).append("</div><div class=\"value\">")
                .append(esc(value)).append("</div>");
        if (note != null && !note.isBlank()) {
            out.append("<div class=\"note\">").append(esc(note)).append("</div>");
        }
        out.append("</div>");
    }

    private void kv(String key, @Nullable String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        out.append("<dt>").append(esc(key)).append("</dt><dd class=\"mono\">").append(esc(value)).append("</dd>");
    }

    private static String location(String location) {
        return "<code class=\"loc\">" + esc(location) + "</code><button class=\"copy\" type=\"button\" data-copy=\""
                + esc(location) + "\" title=\"Copy; paste into your IDE's stack-trace analyzer\">copy</button>";
    }

    private static String meter(double percent) {
        return "<span class=\"meter\" aria-hidden=\"true\"><i style=\"width:"
                + String.format(Locale.ROOT, "%.1f", Math.clamp(percent, 0, 100)) + "%\"></i></span>";
    }

    private static String label(Severity s) {
        return switch (s) {
            case CRITICAL -> "Critical";
            case WARNING -> "Warning";
            case INFO -> "Info";
        };
    }

    private static Function<Double, String> formatter(String unit) {
        return switch (unit) {
            case "bytes" -> Format::bytes;
            case "nanos" -> v -> Format.millis(v / 1e6);
            default -> Format::count;
        };
    }

    private static String unitLabel(String unit) {
        return switch (unit) {
            case "bytes" -> "Bytes";
            case "nanos" -> "Time";
            case "samples" -> "Samples";
            default -> "Events";
        };
    }

    private static String bytesOrDash(@Nullable Long bytes) {
        return bytes == null ? "–" : Format.bytes(bytes);
    }

    private static String time(long offsetMillis) {
        long s = offsetMillis / 1000;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private static @Nullable String join(@Nullable String a, @Nullable String b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : a + " · " + b;
    }

    static String esc(@Nullable String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '&' -> b.append("&amp;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    private static String resource(String name) {
        try (InputStream in = HtmlReportWriter.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
