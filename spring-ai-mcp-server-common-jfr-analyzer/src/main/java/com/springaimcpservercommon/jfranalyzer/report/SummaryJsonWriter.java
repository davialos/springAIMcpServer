package com.springaimcpservercommon.jfranalyzer.report;

import com.springaimcpservercommon.core.json.CanonicalJson;
import com.springaimcpservercommon.jfranalyzer.collect.Format;
import com.springaimcpservercommon.jfranalyzer.model.AnalysisReport;
import com.springaimcpservercommon.jfranalyzer.model.Finding;
import com.springaimcpservercommon.jfranalyzer.model.GcReport;
import com.springaimcpservercommon.jfranalyzer.model.Hotspot;
import com.springaimcpservercommon.jfranalyzer.model.HotspotReport;
import com.springaimcpservercommon.jfranalyzer.model.MemoryReport;
import com.springaimcpservercommon.jfranalyzer.model.ReportMeta;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the shareable summary: the executive summary, headline metrics, findings and the top hot spots per area,
 * and nothing that identifies infrastructure or people. Stack traces, thread names, file paths, network endpoints,
 * JVM arguments and the recording's directory are left out, so the file can go to leadership or other teams as is.
 * Rendered by {@link CanonicalJson} like the full report (ADR-0020).
 */
public final class SummaryJsonWriter {

    /** Version of the summary's JSON shape. */
    public static final String SCHEMA_VERSION = "jfr-analyzer/summary/1";
    static final int TOP = 5;

    private SummaryJsonWriter() {
    }

    /**
     * @param report the report
     * @param pretty indent with two spaces
     * @return JSON text
     */
    public static String write(AnalysisReport report, boolean pretty) {
        String json = CanonicalJson.write(JsonReportWriter.tree(tree(report)));
        return pretty ? JsonReportWriter.indent(json) : json;
    }

    static Map<String, Object> tree(AnalysisReport report) {
        ReportMeta meta = report.meta();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("generatedAt", meta.analyzedAt());
        root.put("privacy", "Excludes stack traces, thread names, file paths, network endpoints and command lines.");

        Map<String, Object> recording = new LinkedHashMap<>();
        String file = meta.recordingFile();
        recording.put("file", file.substring(Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\')) + 1));
        recording.put("start", meta.recordingStart());
        recording.put("end", meta.recordingEnd());
        recording.put("durationMillis", meta.durationMillis());
        recording.put("duration", Format.millis(meta.durationMillis()));
        recording.put("jvmVersion", meta.jvm().jvmVersion());
        recording.put("youngCollector", report.gc().youngCollector());
        recording.put("oldCollector", report.gc().oldCollector());
        recording.put("packages", meta.packages());
        root.put("recording", recording);

        root.put("executiveSummary", report.executiveSummary());
        root.put("metrics", report.summary());
        root.put("gc", gc(report.gc()));
        root.put("memory", memory(report.memory()));

        Map<String, Object> hot = new LinkedHashMap<>();
        hot.put("cpu", top(report.cpu().execution()));
        hot.put("allocation", top(report.memory().allocations()));
        hot.put("lockContention", top(report.threads().monitorEnter()));
        hot.put("parked", top(report.threads().park()));
        hot.put("exceptions", top(report.exceptions().throwSites()));
        root.put("topHotspots", hot);

        root.put("findings", report.findings().stream().map(SummaryJsonWriter::finding).toList());
        return root;
    }

    private static Map<String, Object> gc(GcReport gc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("collections", gc.count());
        m.put("collectionsPerMinute", gc.collectionsPerMinute());
        m.put("totalPauseMs", gc.totalPauseMs());
        m.put("maxPauseMs", gc.maxPauseMs());
        m.put("p95PauseMs", gc.p95PauseMs());
        m.put("p99PauseMs", gc.p99PauseMs());
        m.put("overheadPercent", gc.overheadPercent());
        m.put("byCause", gc.byCause().stream().map(g -> Map.of("cause", g.name(), "count", g.count(),
                "totalPauseMs", g.totalPauseMs(), "maxPauseMs", g.maxPauseMs())).toList());
        return m;
    }

    private static Map<String, Object> memory(MemoryReport mem) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("heapMaxBytes", mem.heapMaxBytes());
        m.put("heapPeakUsedBytes", mem.heapPeakUsedBytes());
        m.put("heapAfterGcAvgBytes", mem.liveSetAvgBytes());
        m.put("heapAfterGcLastBytes", mem.liveSetLastBytes());
        m.put("heapAfterGcGrowthBytesPerMin", mem.liveSetGrowthBytesPerMin());
        m.put("allocatedBytes", mem.allocatedBytes());
        m.put("allocationRateBytesPerSec", mem.allocationRateBytesPerSec());
        m.put("topAllocatedTypes", mem.allocations().topDetails().stream().limit(TOP)
                .map(n -> Map.of("type", n.name(), "bytes", n.weight(), "percent", n.percent())).toList());
        return m;
    }

    private static Map<String, Object> top(HotspotReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("unit", r.unit());
        m.put("total", r.totalWeight());
        m.put("percentInPackages", r.attributedPercent());
        List<Map<String, Object>> rows = r.hotspots().stream().limit(TOP).map(SummaryJsonWriter::hotspot).toList();
        m.put("hotspots", rows);
        return m;
    }

    private static Map<String, Object> hotspot(Hotspot h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rank", h.rank());
        m.put("method", h.method());
        m.put("location", h.location());
        m.put("weight", h.weight());
        m.put("percent", h.percent());
        m.put("topDetail", h.details().isEmpty() ? null : h.details().getFirst().name());
        return m;
    }

    private static Map<String, Object> finding(Finding f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("severity", f.severity());
        m.put("category", f.category());
        m.put("title", f.title());
        m.put("detail", f.detail());
        m.put("location", f.location());
        return m;
    }
}
