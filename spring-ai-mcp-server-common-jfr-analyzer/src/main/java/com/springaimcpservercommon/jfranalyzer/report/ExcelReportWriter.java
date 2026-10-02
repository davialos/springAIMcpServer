package com.springaimcpservercommon.jfranalyzer.report;

import com.springaimcpservercommon.jfranalyzer.collect.ExecutiveSummaryBuilder;
import com.springaimcpservercommon.jfranalyzer.collect.Format;
import com.springaimcpservercommon.jfranalyzer.model.AnalysisReport;
import com.springaimcpservercommon.jfranalyzer.model.ExecutiveSummary;
import com.springaimcpservercommon.jfranalyzer.model.Finding;
import com.springaimcpservercommon.jfranalyzer.model.GcEvent;
import com.springaimcpservercommon.jfranalyzer.model.GcGroup;
import com.springaimcpservercommon.jfranalyzer.model.GcReport;
import com.springaimcpservercommon.jfranalyzer.model.HealthStatus;
import com.springaimcpservercommon.jfranalyzer.model.HeapPoint;
import com.springaimcpservercommon.jfranalyzer.model.HotLine;
import com.springaimcpservercommon.jfranalyzer.model.Hotspot;
import com.springaimcpservercommon.jfranalyzer.model.HotspotReport;
import com.springaimcpservercommon.jfranalyzer.model.JvmInfo;
import com.springaimcpservercommon.jfranalyzer.model.KeyMetric;
import com.springaimcpservercommon.jfranalyzer.model.MemoryReport;
import com.springaimcpservercommon.jfranalyzer.model.ReportMeta;
import com.springaimcpservercommon.jfranalyzer.model.Severity;
import com.springaimcpservercommon.jfranalyzer.model.ThreadReport;
import com.springaimcpservercommon.jfranalyzer.model.TimePoint;
import com.springaimcpservercommon.jfranalyzer.model.TopIssue;
import com.springaimcpservercommon.jfranalyzer.model.WeightedName;
import com.springaimcpservercommon.jfranalyzer.report.xlsx.XlsxChart;
import com.springaimcpservercommon.jfranalyzer.report.xlsx.XlsxSheet;
import com.springaimcpservercommon.jfranalyzer.report.xlsx.XlsxStyles;
import com.springaimcpservercommon.jfranalyzer.report.xlsx.XlsxStyles.Border;
import com.springaimcpservercommon.jfranalyzer.report.xlsx.XlsxStyles.Edge;
import com.springaimcpservercommon.jfranalyzer.report.xlsx.XlsxStyles.Font;
import com.springaimcpservercommon.jfranalyzer.report.xlsx.XlsxStyles.Style;
import com.springaimcpservercommon.jfranalyzer.report.xlsx.XlsxWorkbook;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Renders a report as an Excel workbook for teams and leadership: a dashboard sheet (health banner, KPI tiles, top
 * issues, recommendations, native charts, top hot spots) followed by filterable detail sheets. Stack traces stay in
 * the HTML and JSON reports; command lines are already redacted in the model.
 */
public final class ExcelReportWriter {

    static final String DASHBOARD = "Dashboard";
    static final String FINDINGS = "Findings";
    static final String METRICS = "Key metrics";
    static final String HOTSPOTS = "Hot spots";
    static final String HOT_LINES = "Hot lines";
    static final String GC = "GC";
    static final String MEMORY = "Memory";
    static final String THREADS = "Threads";
    static final String RECORDING = "Recording";
    static final String CHART_DATA = "Chart data";

    private static final double MIB = 1024.0 * 1024.0;
    private static final String FONT = "Calibri";
    private static final String INK = "0B0B0B";
    private static final String INK_2 = "52514E";
    private static final String MUTED = "6F6D68";
    private static final String TILE = "F3F2EE";
    private static final String HEADER = "0D366B";
    private static final String ACCENT = "2A78D6";
    private static final String ORANGE = "EB6834";
    private static final String AQUA = "1BAF7A";
    private static final String BAR = "86B6EF";
    private static final String RULE = "E1E0D9";
    private static final String INT = "#,##0";
    private static final String DEC1 = "#,##0.0";
    private static final String DEC2 = "#,##0.00";
    private static final String PCT = "0.0\"%\"";

    /** The dashboard's KPI tiles, in order. */
    static final List<String> TILE_METRICS = List.of("cpu.jvmAvgPercent", "cpu.hottestMethodPercent",
            "gc.overheadPercent", "gc.maxPauseMs", "heap.liveSetPercentOfMax", "allocation.rateBytesPerSec",
            "threads.lockBlockedMs", "exceptions.perSecond");

    private final AnalysisReport report;
    private final XlsxWorkbook book;
    private final XlsxStyles styles;
    private final Style base;
    private final int title;
    private final int subtitle;
    private final int section;
    private final int link;
    private final int header;
    private final int text;
    private final int textBold;
    private final int textMuted;
    private final int mono;
    private final int intStyle;
    private final int dec1;
    private final int dec2;
    private final int pct;
    private final int kvKey;
    private final int tileValue;
    private final int tileNote;

    private ExcelReportWriter(AnalysisReport report) {
        this.report = report;
        Font font = new Font(FONT, 11, false, false, false, INK);
        this.book = new XlsxWorkbook("JFR performance dashboard", Instant.parse(report.meta().analyzedAt()), font);
        this.styles = book.styles();
        this.base = new Style(font, null, Border.NONE, null, "left", "top", true, 0);
        Border rule = new Border(null, null, null, new Edge("thin", RULE));
        title = styles.of(base.withFont(font(20, true, INK)).withAlign("left", "center", false));
        subtitle = styles.of(base.withFont(font(10, false, MUTED)).withAlign("left", "center", false));
        section = styles.of(base.withFont(font(14, true, INK)).withAlign("left", "bottom", false)
                .withBorder(new Border(null, null, null, new Edge("medium", ACCENT))));
        link = styles.of(base.withFont(new Font(FONT, 10, false, false, true, ACCENT)).withAlign("right", "bottom",
                false).withBorder(new Border(null, null, null, new Edge("medium", ACCENT))));
        header = styles.of(base.withFont(font(10, true, "FFFFFF")).withFill(HEADER).withAlign("left", "center", true));
        text = styles.of(base.withBorder(rule));
        textBold = styles.of(base.withBorder(rule).withFont(font(11, true, INK)));
        textMuted = styles.of(base.withFont(font(10, false, MUTED)));
        mono = styles.of(base.withBorder(rule).withFont(new Font("Consolas", 10, false, false, false, INK)));
        Style number = base.withBorder(rule).withAlign("right", "top", false);
        intStyle = styles.of(number.withFormat(INT));
        dec1 = styles.of(number.withFormat(DEC1));
        dec2 = styles.of(number.withFormat(DEC2));
        pct = styles.of(number.withFormat(PCT));
        kvKey = styles.of(base.withBorder(rule).withFont(font(11, false, INK_2)));
        tileValue = styles.of(base.withFill(TILE).withFont(font(20, true, INK)).withAlign("left", "center", false)
                .withIndent(1));
        tileNote = styles.of(base.withFill(TILE).withFont(font(9, false, INK_2)).withAlign("left", "top", true)
                .withIndent(1));
    }

    /**
     * @param report the report
     * @return the .xlsx bytes
     */
    public static byte[] write(AnalysisReport report) {
        return new ExcelReportWriter(report).render();
    }

    private byte[] render() {
        XlsxSheet dashboard = book.sheet(DASHBOARD);
        XlsxSheet findings = book.sheet(FINDINGS);
        XlsxSheet metrics = book.sheet(METRICS);
        XlsxSheet hotspots = book.sheet(HOTSPOTS);
        XlsxSheet hotLines = book.sheet(HOT_LINES);
        XlsxSheet gc = book.sheet(GC);
        XlsxSheet memory = book.sheet(MEMORY);
        XlsxSheet threads = book.sheet(THREADS);
        XlsxSheet recording = book.sheet(RECORDING);
        XlsxSheet chartData = book.sheet(CHART_DATA);
        ChartData data = chartData(chartData);
        dashboard(dashboard, data);
        findings(findings);
        metrics(metrics);
        hotspots(hotspots);
        hotLines(hotLines);
        gc(gc, report.gc());
        memory(memory, report.memory());
        threads(threads, report.threads());
        recording(recording, report.meta());
        return book.toBytes();
    }

    // ------------------------------------------------------------------ dashboard

    private void dashboard(XlsxSheet d, ChartData data) {
        ExecutiveSummary exec = report.executiveSummary();
        ReportMeta meta = report.meta();
        d.hideGridLines();
        d.tabColor(ACCENT);
        d.landscape();
        d.width(1, 2);
        for (int c = 2; c <= 16; c++) {
            d.width(c, c == 5 || c == 9 || c == 13 ? 2 : 11);
        }
        d.width(17, 2);
        int r = 2;
        d.height(r, 34);
        d.merge(r, 2, r, 16, title);
        d.text(r, 2, "JFR performance dashboard", title);
        r++;
        d.merge(r, 2, r, 16, subtitle);
        d.text(r, 2, fileName(meta.recordingFile()) + "  ·  " + Format.millis(meta.durationMillis()) + " recorded "
                + meta.recordingStart() + "  ·  packages: " + (meta.packages().isEmpty() ? "all"
                : String.join(", ", meta.packages())) + "  ·  generated " + meta.analyzedAt(), subtitle);

        r = 5;
        d.height(r, 32);
        int banner = bannerStyle(exec.status());
        d.merge(r, 2, r, 16, banner);
        d.text(r, 2, statusMark(exec.status()) + "  " + exec.status() + "  ·  Health score " + exec.healthScore()
                + " / 100", banner);
        r++;
        int headline = styles.of(base.withFont(font(11, false, INK)).withAlign("left", "center", true).withIndent(1));
        d.height(r, 34);
        d.merge(r, 2, r, 16, headline);
        d.text(r, 2, ExecutiveSummaryBuilder.withoutPackages(exec.headline()), headline);

        r = 8;
        List<KeyMetric> tiles = TILE_METRICS.stream()
                .flatMap(id -> exec.keyMetrics().stream().filter(m -> m.id().equals(id)))
                .toList();
        for (int i = 0; i < tiles.size(); i++) {
            int row = r + (i / 4) * 4;
            int col = 2 + (i % 4) * 4;
            tile(d, row, col, tiles.get(i));
        }

        r = 8 + ((tiles.size() + 3) / 4) * 4;
        r = sectionTitle(d, r, "Top issues", FINDINGS, "All findings →");
        d.height(r, 20);
        d.merge(r, 2, r, 3, header);
        d.text(r, 2, "Severity", header);
        d.merge(r, 4, r, 10, header);
        d.text(r, 4, "Issue", header);
        d.merge(r, 11, r, 16, header);
        d.text(r, 11, "Where to look", header);
        r++;
        if (exec.topIssues().isEmpty()) {
            d.merge(r, 2, r, 16, text);
            d.text(r, 2, "No warning or critical findings. Informational notes are on the Findings sheet.", text);
            r++;
        }
        for (TopIssue issue : exec.topIssues()) {
            int sev = severityStyle(issue.severity());
            d.merge(r, 2, r, 3, sev);
            d.text(r, 2, severityLabel(issue.severity()), sev);
            d.merge(r, 4, r, 10, text);
            String issueText = "[" + issue.area().toUpperCase(Locale.ROOT) + "] "
                    + ExecutiveSummaryBuilder.withoutPackages(issue.title());
            d.text(r, 4, issueText, text);
            d.merge(r, 11, r, 16, mono);
            d.text(r, 11, issue.location() == null ? "—" : ExecutiveSummaryBuilder.shortLocation(issue.location()),
                    mono);
            d.height(r, rowHeight(Math.max(lines(issueText, 7 * 11 * 0.9), lines(issue.location() == null ? ""
                    : ExecutiveSummaryBuilder.shortLocation(issue.location()), 6 * 11 * 0.75))));
            r++;
        }

        r = sectionTitle(d, r + 1, "Recommendations", null, null);
        int n = 1;
        for (String rec : exec.recommendations()) {
            d.merge(r, 2, r, 16, text);
            String line = n++ + ".  " + ExecutiveSummaryBuilder.withoutPackages(rec);
            d.text(r, 2, line, text);
            d.height(r, rowHeight(lines(line, 14 * 11 * 0.9)));
            r++;
        }

        r = sectionTitle(d, r + 1, "Trends", CHART_DATA, "Chart data →");
        int chartRows = 18;
        chartOrNote(d, r, 2, chartRows, data.heap, "No heap summaries in this recording.");
        chartOrNote(d, r, 10, chartRows, data.cpuLoad, "No CPU load events in this recording.");
        r += chartRows + 1;
        chartOrNote(d, r, 2, chartRows, data.gcPauses, "No garbage collections in this recording.");
        chartOrNote(d, r, 10, chartRows, data.allocationRate, "No allocation samples in this recording.");
        r += chartRows + 1;
        chartOrNote(d, r, 2, chartRows, data.topCpu, "No CPU samples attributed to your packages.");
        chartOrNote(d, r, 10, chartRows, data.topAllocation, "No allocations attributed to your packages.");
        r += chartRows + 1;

        r = sectionTitle(d, r + 1, "Top hot spots in your code", HOTSPOTS, "All hot spots →");
        d.height(r, 20);
        d.merge(r, 2, r, 3, header);
        d.text(r, 2, "Area", header);
        d.merge(r, 4, r, 12, header);
        d.text(r, 4, "Hottest line", header);
        d.merge(r, 13, r, 14, header);
        d.text(r, 13, "Cost", header);
        d.merge(r, 15, r, 16, header);
        d.text(r, 15, "Share", header);
        r++;
        int first = r;
        r = topRows(d, r, "CPU", report.cpu().execution(), 5);
        r = topRows(d, r, "Allocation", report.memory().allocations(), 5);
        r = topRows(d, r, "Lock contention", report.threads().monitorEnter(), 3);
        r = topRows(d, r, "Parked", report.threads().park(), 3);
        r = topRows(d, r, "Exceptions", report.exceptions().throwSites(), 3);
        if (r == first) {
            d.merge(r, 2, r, 16, text);
            d.text(r, 2, "Nothing was attributed to the requested packages.", text);
            r++;
        }
        d.dataBar(first, r - 1, 15, BAR, 0, 100);
        d.merge(r + 1, 2, r + 1, 16, textMuted);
        d.text(r + 1, 2, "Generated by the JFR analyzer (" + meta.schemaVersion() + "). Stack traces and per-line detail"
                + " are in the HTML and JSON reports.", textMuted);
    }

    private void tile(XlsxSheet d, int row, int col, KeyMetric m) {
        Style label = base.withFill(TILE).withFont(font(9, true, INK_2)).withAlign("left", "center", false)
                .withIndent(1).withBorder(new Border(null, null, new Edge("thick", statusColor(m.status())), null));
        int labelStyle = styles.of(label);
        d.height(row, 22);
        d.merge(row, col, row, col + 2, labelStyle);
        d.text(row, col, m.name().toUpperCase(Locale.ROOT), labelStyle);
        d.height(row + 1, 34);
        d.merge(row + 1, col, row + 1, col + 2, tileValue);
        d.text(row + 1, col, m.display(), tileValue);
        d.height(row + 2, 40);
        d.merge(row + 2, col, row + 2, col + 2, tileNote);
        d.text(row + 2, col, statusMark(m.status()) + " " + statusWord(m.status()) + " · " + m.meaning(), tileNote);
    }

    private int topRows(XlsxSheet d, int r, String area, HotspotReport section, int limit) {
        for (Hotspot h : section.hotspots().stream().limit(limit).toList()) {
            d.merge(r, 2, r, 3, textBold);
            d.text(r, 2, area, textBold);
            d.merge(r, 4, r, 12, mono);
            d.text(r, 4, ExecutiveSummaryBuilder.shortLocation(h.location()), mono);
            d.merge(r, 13, r, 14, text);
            d.text(r, 13, display(section.unit(), h.weight()), styles.of(base.withBorder(
                    new Border(null, null, null, new Edge("thin", RULE))).withAlign("right", "top", false)));
            d.merge(r, 15, r, 16, pct);
            d.number(r, 15, round(h.percent()), pct);
            r++;
        }
        return r;
    }

    private int sectionTitle(XlsxSheet d, int row, String name, @Nullable String target, @Nullable String label) {
        d.height(row, 26);
        d.merge(row, 2, row, target == null ? 16 : 13, section);
        d.text(row, 2, name, section);
        if (target != null && label != null) {
            d.merge(row, 14, row, 16, link);
            d.text(row, 14, label, link);
            d.link(row, 14, target, "Open the " + target + " sheet");
        }
        return row + 1;
    }

    private void chartOrNote(XlsxSheet d, int row, int col, int rows, @Nullable XlsxChart chart, String empty) {
        if (chart == null) {
            int style = styles.of(base.withFill(TILE).withFont(font(10, false, MUTED)).withAlign("center", "center",
                    true));
            d.merge(row, col, row + rows - 1, col + 6, style);
            d.text(row, col, empty, style);
            return;
        }
        XlsxChart.Anchor at = new XlsxChart.Anchor(row, col, row + rows, col + 7);
        d.chart(switch (chart) {
            case XlsxChart.Scatter sc -> new XlsxChart.Scatter(sc.title(), sc.xMax(), sc.xTitle(), sc.yTitle(),
                    sc.xFormat(), sc.yFormat(), sc.series(), at);
            case XlsxChart.Bar b -> new XlsxChart.Bar(b.title(), b.seriesName(), b.color(), b.categories(),
                    b.values(), b.valueFormat(), at);
        });
    }

    // ------------------------------------------------------------------ chart data

    private static final class ChartData {
        @Nullable XlsxChart heap;
        @Nullable XlsxChart cpuLoad;
        @Nullable XlsxChart gcPauses;
        @Nullable XlsxChart allocationRate;
        @Nullable XlsxChart topCpu;
        @Nullable XlsxChart topAllocation;
    }

    private ChartData chartData(XlsxSheet s) {
        ChartData data = new ChartData();
        s.freezeRows(1);
        s.tabColor("898781");
        MemoryReport m = report.memory();
        double durationSec = report.meta().durationMillis() / 1000.0;
        Double xMax = Math.ceil(durationSec);

        List<HeapPoint> heap = m.heapTimeline();
        List<double[]> used = heap.stream().map(p -> new double[]{p.offsetMillis() / 1000.0, p.usedBytes() / MIB})
                .toList();
        List<double[]> after = heap.stream().filter(p -> p.when().startsWith("After"))
                .map(p -> new double[]{p.offsetMillis() / 1000.0, p.usedBytes() / MIB}).toList();
        XlsxChart.NumberRange[] usedCols = columns(s, 1, "Heap time (s)", "Heap used (MiB)", used);
        XlsxChart.NumberRange[] afterCols = columns(s, 3, "After-GC time (s)", "Heap after GC (MiB)", after);
        List<double[]> maxLine = m.heapMaxBytes() == null || used.isEmpty() ? List.of()
                : List.of(new double[]{0, m.heapMaxBytes() / MIB}, new double[]{durationSec, m.heapMaxBytes() / MIB});
        XlsxChart.NumberRange[] maxCols = columns(s, 5, "Max-heap time (s)", "Max heap (MiB)", maxLine);
        if (!used.isEmpty()) {
            List<XlsxChart.Series> series = new ArrayList<>();
            series.add(new XlsxChart.Series("Heap used", ACCENT, usedCols[0], usedCols[1], true, false));
            if (!after.isEmpty()) {
                series.add(new XlsxChart.Series("After GC", ORANGE, afterCols[0], afterCols[1], false, true));
            }
            if (!maxLine.isEmpty()) {
                series.add(new XlsxChart.Series("Max heap", "898781", maxCols[0], maxCols[1], true, false));
            }
            data.heap = new XlsxChart.Scatter("Heap used (MiB)", xMax, "Seconds since start", "", "0", INT, series,
                    UNPLACED);
        }

        List<double[]> jvm = points(report.cpu().jvmCpuTimeline(), 1);
        List<double[]> machine = points(report.cpu().machineCpuTimeline(), 1);
        XlsxChart.NumberRange[] jvmCols = columns(s, 7, "CPU time (s)", "JVM CPU %", jvm);
        XlsxChart.NumberRange[] machineCols = columns(s, 9, "Machine time (s)", "Machine CPU %", machine);
        if (!jvm.isEmpty()) {
            data.cpuLoad = new XlsxChart.Scatter("CPU load (%)", xMax, "Seconds since start", "", "0", "0",
                    List.of(
                    new XlsxChart.Series("JVM", ACCENT, jvmCols[0], jvmCols[1], true, false),
                    new XlsxChart.Series("Machine", ORANGE, machineCols[0], machineCols[1], true, false)),
                    UNPLACED);
        }

        List<double[]> pauses = points(report.gc().pauseTimeline(), 1);
        XlsxChart.NumberRange[] gcCols = columns(s, 11, "GC time (s)", "GC pause (ms)", pauses);
        if (!pauses.isEmpty()) {
            data.gcPauses = new XlsxChart.Scatter("GC pauses (ms)", xMax, "Seconds since start", "", "0", DEC1,
                    List.of(new XlsxChart.Series("Pause", ACCENT, gcCols[0], gcCols[1], false, true)), UNPLACED);
        }

        List<double[]> alloc = points(m.allocationRateTimeline(), MIB);
        XlsxChart.NumberRange[] allocCols = columns(s, 13, "Allocation time (s)", "Allocation rate (MiB/s)", alloc);
        if (!alloc.isEmpty()) {
            data.allocationRate = new XlsxChart.Scatter("Allocation rate (MiB/s)", xMax, "Seconds since start",
                    "", "0", INT, List.of(new XlsxChart.Series("Allocation rate", AQUA, allocCols[0], allocCols[1], true,
                    false)), UNPLACED);
        }

        data.topCpu = bars(s, 15, "Top CPU hot spots (% of samples)", "CPU share", report.cpu().execution(),
                UNPLACED);
        data.topAllocation = bars(s, 17, "Top allocation sites (% of bytes)", "Allocation share",
                report.memory().allocations(), UNPLACED);
        for (int c = 1; c <= 18; c++) {
            s.width(c, c == 15 || c == 17 ? 44 : 18);
        }
        return data;
    }

    private @Nullable XlsxChart bars(XlsxSheet s, int col, String chartTitle, String series, HotspotReport r,
                                     XlsxChart.Anchor anchor) {
        List<Hotspot> top = r.hotspots().stream().limit(8).toList();
        s.text(1, col, chartTitle.replace(" (% of samples)", "").replace(" (% of bytes)", ""), header);
        s.text(1, col + 1, "Share %", header);
        if (top.isEmpty()) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        double[] values = new double[top.size()];
        for (int i = 0; i < top.size(); i++) {
            Hotspot h = top.get(i);
            String label = shortMethod(h);
            labels.add(label);
            values[i] = round(h.percent());
            s.text(i + 2, col, label, text);
            s.number(i + 2, col + 1, values[i], pct);
        }
        return new XlsxChart.Bar(chartTitle, series, ACCENT, new XlsxChart.TextRange(CHART_DATA, col, 2, labels),
                new XlsxChart.NumberRange(CHART_DATA, col + 1, 2, values), PCT, anchor);
    }

    private XlsxChart.NumberRange[] columns(XlsxSheet s, int col, String xName, String yName, List<double[]> pts) {
        s.text(1, col, xName, header);
        s.text(1, col + 1, yName, header);
        double[] x = new double[pts.size()];
        double[] y = new double[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            x[i] = round(pts.get(i)[0]);
            y[i] = round(pts.get(i)[1]);
            s.number(i + 2, col, x[i], dec1);
            s.number(i + 2, col + 1, y[i], dec2);
        }
        return new XlsxChart.NumberRange[]{new XlsxChart.NumberRange(CHART_DATA, col, 2, x),
                new XlsxChart.NumberRange(CHART_DATA, col + 1, 2, y)};
    }

    private static List<double[]> points(List<TimePoint> timeline, double divisor) {
        return timeline.stream().map(p -> new double[]{p.offsetMillis() / 1000.0, p.value() / divisor}).toList();
    }

    /** Placeholder anchor; the dashboard moves each chart to its slot (see {@link #chartOrNote}). */
    private static final XlsxChart.Anchor UNPLACED = new XlsxChart.Anchor(1, 1, 2, 2);

    // ------------------------------------------------------------------ detail sheets

    private void findings(XlsxSheet s) {
        s.tabColor("D03B3B");
        int[] widths = {5, 14, 12, 48, 44, 60, 60};
        String[] heads = {"#", "Severity", "Area", "Finding", "Impact", "What to do", "Where to look"};
        tableHeader(s, 1, heads, widths);
        int r = 2;
        for (Finding f : report.findings()) {
            s.number(r, 1, r - 1, intStyle);
            s.text(r, 2, severityLabel(f.severity()), severityStyle(f.severity()));
            s.text(r, 3, f.category(), text);
            s.text(r, 4, f.title(), textBold);
            String impact = impactOf(f);
            s.text(r, 5, impact, text);
            s.text(r, 6, f.detail(), text);
            s.text(r, 7, f.location() == null ? "" : f.location(), mono);
            s.height(r, rowHeight(Math.max(Math.max(lines(f.title(), 48), lines(impact, 44)),
                    Math.max(lines(f.detail(), 60), lines(f.location() == null ? "" : f.location(), 60 * 0.85)))));
            r++;
        }
        finishTable(s, heads.length, r);
    }

    private void metrics(XlsxSheet s) {
        s.tabColor(ACCENT);
        int[] widths = {34, 14, 26, 16, 16, 70, 30};
        String[] heads = {"Metric", "Status", "Value", "Raw value", "Unit", "Why it matters", "ID"};
        tableHeader(s, 1, heads, widths);
        int r = 2;
        for (KeyMetric m : report.executiveSummary().keyMetrics()) {
            s.text(r, 1, m.name(), textBold);
            s.text(r, 2, statusMark(m.status()) + " " + statusWord(m.status()), statusStyle(m.status()));
            s.text(r, 3, m.display(), text);
            if (m.value() != null) {
                s.number(r, 4, round(m.value()), dec2);
            } else {
                s.blank(r, 4, text);
            }
            s.text(r, 5, m.unit(), text);
            s.text(r, 6, m.meaning(), text);
            s.text(r, 7, m.id(), mono);
            r++;
        }
        finishTable(s, heads.length, r);
    }

    private void hotspots(XlsxSheet s) {
        s.tabColor(ORANGE);
        int[] widths = {20, 6, 52, 70, 12, 9, 10, 10, 9, 48, 40, 26};
        String[] heads = {"Area", "Rank", "Method", "Hottest line", "Cost", "Unit", "Share %", "Events", "Self %",
                "Cost paid in (top frame)", "Top detail", "Top thread"};
        tableHeader(s, 1, heads, widths);
        int r = 2;
        for (Map.Entry<String, HotspotReport> e : sections().entrySet()) {
            HotspotReport section = e.getValue();
            for (Hotspot h : section.hotspots()) {
                s.text(r, 1, e.getKey(), textBold);
                s.number(r, 2, h.rank(), intStyle);
                s.text(r, 3, h.method(), mono);
                s.text(r, 4, h.location(), mono);
                s.number(r, 5, round(convert(section.unit(), h.weight())), unitStyle(section.unit()));
                s.text(r, 6, unitName(section.unit()), text);
                s.number(r, 7, round(h.percent()), pct);
                s.number(r, 8, h.count(), intStyle);
                s.number(r, 9, round(h.weight() <= 0 ? 0 : h.selfWeight() * 100 / h.weight()), pct);
                s.text(r, 10, h.callees().isEmpty() ? "" : h.callees().getFirst().name(), mono);
                s.text(r, 11, h.details().isEmpty() ? "" : section.detailLabel() + ": " + h.details().getFirst().name(),
                        text);
                s.text(r, 12, h.threads().isEmpty() ? "" : h.threads().getFirst().name(), text);
                r++;
            }
        }
        if (r == 2) {
            s.text(2, 1, "Nothing was attributed to the requested packages.", text);
            r = 3;
        }
        s.dataBar(2, r - 1, 7, BAR, 0, 100);
        finishTable(s, heads.length, r);
    }

    private void hotLines(XlsxSheet s) {
        s.tabColor(ORANGE);
        int[] widths = {20, 80, 60, 8, 12, 9, 10, 10};
        String[] heads = {"Area", "Line", "Method", "Line no.", "Cost", "Unit", "Share %", "Events"};
        tableHeader(s, 1, heads, widths);
        int r = 2;
        for (Map.Entry<String, HotspotReport> e : sections().entrySet()) {
            HotspotReport section = e.getValue();
            for (HotLine l : section.hotLines()) {
                s.text(r, 1, e.getKey(), textBold);
                s.text(r, 2, l.location(), mono);
                s.text(r, 3, l.method(), mono);
                s.number(r, 4, l.line(), intStyle);
                s.number(r, 5, round(convert(section.unit(), l.weight())), unitStyle(section.unit()));
                s.text(r, 6, unitName(section.unit()), text);
                s.number(r, 7, round(l.percent()), pct);
                s.number(r, 8, l.count(), intStyle);
                r++;
            }
        }
        if (r == 2) {
            s.text(2, 1, "Nothing was attributed to the requested packages.", text);
            r = 3;
        }
        s.dataBar(2, r - 1, 7, BAR, 0, 100);
        finishTable(s, heads.length, r);
    }

    private void gc(XlsxSheet s, GcReport gc) {
        s.tabColor(AQUA);
        s.width(1, 30);
        for (int c = 2; c <= 10; c++) {
            s.width(c, 16);
        }
        s.width(3, 26);
        int r = 1;
        r = kvTitle(s, r, "Garbage collection");
        r = kv(s, r, "Young collector", gc.youngCollector());
        r = kv(s, r, "Old collector", gc.oldCollector());
        r = kvNumber(s, r, "Collections", gc.count(), intStyle);
        r = kvNumber(s, r, "Collections per minute", gc.collectionsPerMinute(), dec1);
        r = kvNumber(s, r, "Total pause (ms)", gc.totalPauseMs(), dec1);
        r = kvNumber(s, r, "Time paused (% of recording)", gc.overheadPercent(), pct);
        r = kvNumber(s, r, "Max pause (ms)", gc.maxPauseMs(), dec1);
        r = kvNumber(s, r, "Average pause (ms)", gc.avgPauseMs(), dec1);
        r = kvNumber(s, r, "p50 pause (ms)", gc.p50PauseMs(), dec1);
        r = kvNumber(s, r, "p95 pause (ms)", gc.p95PauseMs(), dec1);
        r = kvNumber(s, r, "p99 pause (ms)", gc.p99PauseMs(), dec1);
        r = kvNumber(s, r, "GC time incl. concurrent (ms)", gc.totalDurationMs(), dec1);
        r++;
        r = groups(s, r, "By collector", gc.byCollector());
        r++;
        r = groups(s, r, "By cause", gc.byCause());
        r++;
        r = kvTitle(s, r, "Longest pauses");
        String[] heads = {"GC id", "Collector", "Cause", "At (s)", "Pause (ms)", "Duration (ms)", "Heap before (MiB)",
                "Heap after (MiB)", "Reclaimed (MiB)"};
        tableHeader(s, r, heads, null);
        r++;
        for (GcEvent e : gc.longestPauses()) {
            s.number(r, 1, e.gcId(), intStyle);
            s.text(r, 2, e.name(), text);
            s.text(r, 3, e.cause(), text);
            s.number(r, 4, round(e.offsetMillis() / 1000.0), dec1);
            s.number(r, 5, round(e.pauseMs()), dec2);
            s.number(r, 6, round(e.durationMs()), dec2);
            mib(s, r, 7, e.heapBeforeBytes());
            mib(s, r, 8, e.heapAfterBytes());
            mib(s, r, 9, e.reclaimedBytes());
            r++;
        }
    }

    private int groups(XlsxSheet s, int r, String name, List<GcGroup> groups) {
        r = kvTitle(s, r, name);
        tableHeader(s, r, new String[]{"Name", "Count", "Total pause (ms)", "Max (ms)", "Avg (ms)", "Duration (ms)"},
                null);
        r++;
        for (GcGroup g : groups) {
            s.text(r, 1, g.name(), text);
            s.number(r, 2, g.count(), intStyle);
            s.number(r, 3, round(g.totalPauseMs()), dec2);
            s.number(r, 4, round(g.maxPauseMs()), dec2);
            s.number(r, 5, round(g.avgPauseMs()), dec2);
            s.number(r, 6, round(g.totalDurationMs()), dec2);
            r++;
        }
        return r;
    }

    private void memory(XlsxSheet s, MemoryReport m) {
        s.tabColor(AQUA);
        s.width(1, 34);
        s.width(2, 60);
        s.width(3, 16);
        s.width(4, 12);
        s.width(5, 10);
        int r = 1;
        r = kvTitle(s, r, "Heap");
        r = kvMib(s, r, "Max heap (MiB)", m.heapMaxBytes());
        r = kvMib(s, r, "Initial heap (MiB)", m.heapInitialBytes());
        r = kvMib(s, r, "Peak used (MiB)", m.heapPeakUsedBytes());
        r = kvMib(s, r, "Peak committed (MiB)", m.heapPeakCommittedBytes());
        r = kvMib(s, r, "Heap after GC, min (MiB)", m.liveSetMinBytes());
        r = kvMib(s, r, "Heap after GC, average (MiB)", m.liveSetAvgBytes());
        r = kvMib(s, r, "Heap after GC, max (MiB)", m.liveSetMaxBytes());
        r = kvMib(s, r, "Heap after GC, first (MiB)", m.liveSetFirstBytes());
        r = kvMib(s, r, "Heap after GC, last (MiB)", m.liveSetLastBytes());
        if (m.liveSetGrowthBytesPerMin() != null) {
            r = kvNumber(s, r, "Heap-after-GC growth (MiB/min)", m.liveSetGrowthBytesPerMin() / MIB, dec2);
        }
        r = kvMib(s, r, "Metaspace used, max (MiB)", m.metaspaceUsedMaxBytes());
        r = kvMib(s, r, "Metaspace committed, max (MiB)", m.metaspaceCommittedMaxBytes());
        r++;
        r = kvTitle(s, r, "Allocation");
        r = kvMib(s, r, "Allocated (MiB)", m.allocatedBytes());
        if (m.allocationRateBytesPerSec() != null) {
            r = kvNumber(s, r, "Allocation rate (MiB/s)", m.allocationRateBytesPerSec() / MIB, dec1);
        }
        r = kv(s, r, "Source", m.allocationSource());
        r++;
        r = names(s, r, "Top allocated types", "Type", m.allocations().topDetails(), m.allocations().unit());
        r++;
        names(s, r, "Long-lived objects (leak candidates)", "Type", m.oldObjects().topDetails(),
                m.oldObjects().unit());
    }

    private void threads(XlsxSheet s, ThreadReport t) {
        s.tabColor(AQUA);
        s.width(1, 40);
        s.width(2, 60);
        s.width(3, 16);
        s.width(4, 12);
        s.width(5, 10);
        int r = 1;
        r = kvTitle(s, r, "Threads");
        r = kvLong(s, r, "Peak live threads", t.peakThreads());
        r = kvLong(s, r, "Live threads at end", t.lastActiveThreads());
        r = kvLong(s, r, "Daemon threads at end", t.daemonThreads());
        r = kvLong(s, r, "Threads started since JVM start", t.startedThreads());
        r = kvNumber(s, r, "Blocked on contended monitors (ms)", t.monitorEnter().totalWeight() / 1e6, dec1);
        r = kvNumber(s, r, "Parked, all threads (ms)", t.park().totalWeight() / 1e6, dec1);
        r = kvNumber(s, r, "Parked in your packages (ms)", t.park().attributedWeight() / 1e6, dec1);
        r = kvNumber(s, r, "Object.wait() (ms)", t.monitorWait().totalWeight() / 1e6, dec1);
        r = kvNumber(s, r, "Virtual-thread pinning events", t.pinned().events(), intStyle);
        r++;
        r = names(s, r, "Contended monitor classes", "Monitor class", t.monitorEnter().topDetails(), "nanos");
        r++;
        r = names(s, r, "Threads holding contended monitors", "Owner thread", t.lockOwners(), "nanos");
        r++;
        r = names(s, r, "Blocked time by thread", "Thread", t.blockedByThread(), "nanos");
        r++;
        names(s, r, "Park blockers", "Blocker class", t.park().topDetails(), "nanos");
    }

    private void recording(XlsxSheet s, ReportMeta meta) {
        s.tabColor("898781");
        s.width(1, 28);
        s.width(2, 100);
        JvmInfo jvm = meta.jvm();
        int r = 1;
        r = kvTitle(s, r, "Recording");
        r = kv(s, r, "File", fileName(meta.recordingFile()));
        r = kv(s, r, "Size", Format.bytes(meta.fileSizeBytes()));
        r = kv(s, r, "Start", meta.recordingStart());
        r = kv(s, r, "End", meta.recordingEnd());
        r = kv(s, r, "Duration", Format.millis(meta.durationMillis()));
        r = kv(s, r, "Packages", meta.packages().isEmpty() ? "(all)" : String.join(", ", meta.packages()));
        r = kv(s, r, "Excluded", String.join(", ", meta.excludedPackages()));
        r = kv(s, r, "Analyzed at", meta.analyzedAt());
        r = kv(s, r, "Report schema", meta.schemaVersion());
        r++;
        r = kvTitle(s, r, "JVM");
        r = kv(s, r, "JVM", jvm.jvmName());
        r = kv(s, r, "Version", jvm.jvmVersion());
        r = kv(s, r, "OS", jvm.os());
        r = kv(s, r, "CPU", jvm.cpu());
        r = kv(s, r, "Hardware threads", jvm.hardwareThreads() == null ? null : String.valueOf(jvm.hardwareThreads()));
        r = kv(s, r, "Physical memory", jvm.physicalMemoryBytes() == null ? null
                : Format.bytes(jvm.physicalMemoryBytes()));
        r = kv(s, r, "JVM arguments (secrets masked)", jvm.jvmArguments());
        r++;
        r = kvTitle(s, r, "Events in the recording");
        tableHeader(s, r, new String[]{"Event type", "Count"}, null);
        r++;
        for (Map.Entry<String, Long> e : meta.eventCounts().entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).toList()) {
            s.text(r, 1, e.getKey(), mono);
            s.number(r, 2, e.getValue(), intStyle);
            r++;
        }
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, HotspotReport> sections() {
        Map<String, HotspotReport> m = new LinkedHashMap<>();
        m.put("CPU", report.cpu().execution());
        m.put("Native code", report.cpu().nativeSamples());
        m.put("Allocation", report.memory().allocations());
        m.put("Allocation → GC", report.memory().allocationsRequiringGc());
        m.put("Long-lived objects", report.memory().oldObjects());
        m.put("Lock contention", report.threads().monitorEnter());
        m.put("Parked", report.threads().park());
        m.put("Object.wait", report.threads().monitorWait());
        m.put("Sleep", report.threads().sleep());
        m.put("Virtual-thread pinning", report.threads().pinned());
        m.put("Socket read", report.io().socketRead());
        m.put("Socket write", report.io().socketWrite());
        m.put("File read", report.io().fileRead());
        m.put("File write", report.io().fileWrite());
        m.put("Exceptions", report.exceptions().throwSites());
        return m;
    }

    private int names(XlsxSheet s, int r, String name, String column, List<WeightedName> rows, String unit) {
        r = kvTitle(s, r, name);
        tableHeader(s, r, new String[]{column, "Cost (" + unitName(unit) + ")", "Share %", "Events"}, null);
        r++;
        if (rows.isEmpty()) {
            s.text(r, 1, "None recorded.", textMuted);
            return r + 1;
        }
        int first = r;
        for (WeightedName n : rows) {
            s.text(r, 1, n.name(), mono);
            s.number(r, 2, round(convert(unit, n.weight())), unitStyle(unit));
            s.number(r, 3, round(n.percent()), pct);
            s.number(r, 4, n.count(), intStyle);
            r++;
        }
        s.dataBar(first, r - 1, 3, BAR, 0, 100);
        return r;
    }

    private void tableHeader(XlsxSheet s, int row, String[] heads, int @Nullable [] widths) {
        s.height(row, 22);
        for (int i = 0; i < heads.length; i++) {
            s.text(row, i + 1, heads[i], header);
            if (widths != null) {
                s.width(i + 1, widths[i]);
            }
        }
    }

    private static void finishTable(XlsxSheet s, int columns, int nextRow) {
        s.freezeRows(1);
        s.autoFilter(1, 1, Math.max(2, nextRow - 1), columns);
        s.landscape();
    }

    private int kvTitle(XlsxSheet s, int r, String name) {
        int st = styles.of(base.withFont(font(13, true, INK)).withAlign("left", "bottom", false)
                .withBorder(new Border(null, null, null, new Edge("medium", ACCENT))));
        s.height(r, 24);
        s.text(r, 1, name, st);
        s.blank(r, 2, st);
        return r + 1;
    }

    private int kv(XlsxSheet s, int r, String key, @Nullable String value) {
        if (value == null || value.isBlank()) {
            return r;
        }
        s.text(r, 1, key, kvKey);
        s.text(r, 2, value, text);
        if (value.length() > 100) {
            s.height(r, rowHeight(lines(value, 100)));
        }
        return r + 1;
    }

    private int kvNumber(XlsxSheet s, int r, String key, double value, int style) {
        s.text(r, 1, key, kvKey);
        s.number(r, 2, round(value), style);
        return r + 1;
    }

    private int kvLong(XlsxSheet s, int r, String key, @Nullable Long value) {
        return value == null ? r : kvNumber(s, r, key, value, intStyle);
    }

    private int kvMib(XlsxSheet s, int r, String key, @Nullable Long bytes) {
        return bytes == null ? r : kvNumber(s, r, key, bytes / MIB, dec1);
    }

    private void mib(XlsxSheet s, int r, int c, @Nullable Long bytes) {
        if (bytes == null) {
            s.blank(r, c, dec1);
        } else {
            s.number(r, c, round(bytes / MIB), dec1);
        }
    }

    private int unitStyle(String unit) {
        return "samples".equals(unit) || "events".equals(unit) ? intStyle : dec1;
    }

    private static double convert(String unit, double weight) {
        return switch (unit) {
            case "bytes" -> weight / MIB;
            case "nanos" -> weight / 1e6;
            default -> weight;
        };
    }

    private static String unitName(String unit) {
        return switch (unit) {
            case "bytes" -> "MiB";
            case "nanos" -> "ms";
            default -> unit;
        };
    }

    private static String display(String unit, double weight) {
        return switch (unit) {
            case "bytes" -> Format.bytes(weight);
            case "nanos" -> Format.millis(weight / 1e6);
            case "samples" -> Format.count(weight) + " samples";
            default -> Format.count(weight) + " events";
        };
    }

    private String impactOf(Finding f) {
        return report.executiveSummary().topIssues().stream()
                .filter(i -> i.title().equals(f.title()))
                .map(TopIssue::impact)
                .findFirst()
                .orElse("");
    }

    private int severityStyle(Severity s) {
        String fill = switch (s) {
            case CRITICAL -> "FCE4E4";
            case WARNING -> "FEF3D6";
            case INFO -> "E8F0FC";
        };
        return styles.of(base.withFill(fill).withFont(font(10, true, INK))
                .withBorder(new Border(new Edge("thick", switch (s) {
                    case CRITICAL -> "D03B3B";
                    case WARNING -> "FAB219";
                    case INFO -> ACCENT;
                }), null, null, new Edge("thin", RULE))));
    }

    private int statusStyle(HealthStatus s) {
        String fill = switch (s) {
            case RED -> "FCE4E4";
            case AMBER -> "FEF3D6";
            case GREEN -> "E6F4EA";
            case UNKNOWN -> TILE;
        };
        return styles.of(base.withFill(fill).withFont(font(10, true, INK))
                .withBorder(new Border(new Edge("thick", statusColor(s)), null, null, new Edge("thin", RULE))));
    }

    private int bannerStyle(HealthStatus s) {
        String fill = switch (s) {
            case RED -> "B42318";
            case AMBER -> "FAB219";
            case GREEN -> "1E7B34";
            case UNKNOWN -> "6F6D68";
        };
        String ink = s == HealthStatus.AMBER ? INK : "FFFFFF";
        return styles.of(base.withFill(fill).withFont(font(15, true, ink)).withAlign("left", "center", false)
                .withIndent(1));
    }

    private static String statusColor(HealthStatus s) {
        return switch (s) {
            case RED -> "D03B3B";
            case AMBER -> "FAB219";
            case GREEN -> "0CA30C";
            case UNKNOWN -> "C3C2B7";
        };
    }

    private static String statusMark(HealthStatus s) {
        return switch (s) {
            case RED -> "✖";
            case AMBER -> "▲";
            case GREEN -> "●";
            case UNKNOWN -> "○";
        };
    }

    private static String statusWord(HealthStatus s) {
        return switch (s) {
            case RED -> "Act now";
            case AMBER -> "Watch";
            case GREEN -> "Good";
            case UNKNOWN -> "Not recorded";
        };
    }

    private static String severityLabel(Severity s) {
        return switch (s) {
            case CRITICAL -> "✖ Critical";
            case WARNING -> "▲ Warning";
            case INFO -> "ℹ Info";
        };
    }

    private static Font font(double size, boolean bold, String color) {
        return new Font(FONT, size, bold, false, false, color);
    }

    private static String shortMethod(Hotspot h) {
        String cls = h.className().substring(h.className().lastIndexOf('.') + 1);
        String label = cls + "." + h.methodName();
        return label.length() > 48 ? "…" + label.substring(label.length() - 47) : label;
    }

    private static String fileName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return path.substring(slash + 1);
    }

    /** Excel does not fit row heights on open, so wrapped rows get an estimated height. */
    static int lines(String text, double widthChars) {
        int lines = 0;
        for (String part : text.split("\n", -1)) {
            lines += Math.max(1, (int) Math.ceil(part.length() / Math.max(1, widthChars)));
        }
        return Math.max(1, lines);
    }

    private static double rowHeight(int lines) {
        return Math.max(20, 15.0 * lines + 5);
    }

    private static double round(double v) {
        return Double.isFinite(v) ? Math.round(v * 100) / 100.0 : 0;
    }
}
