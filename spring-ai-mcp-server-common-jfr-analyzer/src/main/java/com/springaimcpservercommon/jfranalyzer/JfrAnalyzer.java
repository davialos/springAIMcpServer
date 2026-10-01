package com.springaimcpservercommon.jfranalyzer;

import com.springaimcpservercommon.jfranalyzer.collect.CpuCollector;
import com.springaimcpservercommon.jfranalyzer.collect.EventCollector;
import com.springaimcpservercommon.jfranalyzer.collect.ExceptionCollector;
import com.springaimcpservercommon.jfranalyzer.collect.FindingsEngine;
import com.springaimcpservercommon.jfranalyzer.collect.GcCollector;
import com.springaimcpservercommon.jfranalyzer.collect.IoCollector;
import com.springaimcpservercommon.jfranalyzer.collect.JvmInfoCollector;
import com.springaimcpservercommon.jfranalyzer.collect.Limits;
import com.springaimcpservercommon.jfranalyzer.collect.MemoryCollector;
import com.springaimcpservercommon.jfranalyzer.collect.PackageMatcher;
import com.springaimcpservercommon.jfranalyzer.collect.Span;
import com.springaimcpservercommon.jfranalyzer.collect.StackResolver;
import com.springaimcpservercommon.jfranalyzer.collect.ThreadCollector;
import com.springaimcpservercommon.jfranalyzer.model.AnalysisReport;
import com.springaimcpservercommon.jfranalyzer.model.CpuReport;
import com.springaimcpservercommon.jfranalyzer.model.ExceptionReport;
import com.springaimcpservercommon.jfranalyzer.model.Finding;
import com.springaimcpservercommon.jfranalyzer.model.GcReport;
import com.springaimcpservercommon.jfranalyzer.model.Hotspot;
import com.springaimcpservercommon.jfranalyzer.model.HotspotReport;
import com.springaimcpservercommon.jfranalyzer.model.IoReport;
import com.springaimcpservercommon.jfranalyzer.model.MemoryReport;
import com.springaimcpservercommon.jfranalyzer.model.ReportMeta;
import com.springaimcpservercommon.jfranalyzer.model.Severity;
import com.springaimcpservercommon.jfranalyzer.model.Summary;
import com.springaimcpservercommon.jfranalyzer.model.ThreadReport;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads a JFR recording once, streaming event by event (memory stays proportional to the number of distinct stacks,
 * not to the file size), and builds an {@link AnalysisReport} attributed to the requested packages.
 */
public final class JfrAnalyzer {

    private final AnalyzerOptions options;
    private final Clock clock;

    /** @param options what to analyze and how */
    public JfrAnalyzer(AnalyzerOptions options) {
        this(options, Clock.systemUTC());
    }

    /**
     * @param options what to analyze and how
     * @param clock   clock for the {@code analyzedAt} stamp
     */
    public JfrAnalyzer(AnalyzerOptions options, Clock clock) {
        this.options = options;
        this.clock = clock;
    }

    /**
     * @return the report
     * @throws IOException if the file cannot be read or is not a JFR recording
     */
    public AnalysisReport analyze() throws IOException {
        Limits limits = new Limits(options.topN(), options.stacksPerHotspot(), options.stackDepth());
        PackageMatcher matcher = new PackageMatcher(options.packages(), options.excludedPackages());
        StackResolver stacks = new StackResolver(matcher);
        CpuCollector cpu = new CpuCollector(stacks, limits);
        MemoryCollector memory = new MemoryCollector(stacks, limits);
        GcCollector gc = new GcCollector(limits);
        ThreadCollector threads = new ThreadCollector(stacks, limits);
        IoCollector io = new IoCollector(stacks, limits);
        ExceptionCollector exceptions = new ExceptionCollector(stacks, limits);
        JvmInfoCollector jvm = new JvmInfoCollector();
        List<EventCollector> collectors = List.of(cpu, memory, gc, threads, io, exceptions, jvm);

        Map<String, Long> counts = new TreeMap<>();
        Instant first = null;
        Instant last = null;
        try (RecordingFile file = new RecordingFile(options.recording())) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                String type = event.getEventType().getName();
                counts.merge(type, 1L, Long::sum);
                Instant start = event.getStartTime();
                Instant end = event.getEndTime();
                if (first == null || start.isBefore(first)) {
                    first = start;
                }
                if (last == null || end.isAfter(last)) {
                    last = end;
                }
                for (EventCollector collector : collectors) {
                    collector.accept(type, event);
                }
            }
        }
        if (first == null) {
            first = Instant.EPOCH;
            last = Instant.EPOCH;
        }
        Span span = new Span(first, last);

        CpuReport cpuReport = cpu.build(span);
        MemoryReport memoryReport = memory.build(span);
        GcReport gcReport = gc.build(span);
        ThreadReport threadReport = threads.build();
        IoReport ioReport = io.build();
        ExceptionReport exceptionReport = exceptions.build(span);
        List<Finding> findings = FindingsEngine.evaluate(matcher.restricted(), span, counts, cpuReport,
                memoryReport, gcReport, threadReport, ioReport, exceptionReport);

        ReportMeta meta = new ReportMeta(options.recording().toAbsolutePath().normalize().toString(),
                Files.size(options.recording()), Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).toString(),
                options.packages(), options.excludedPackages(), first.toString(), last.toString(), span.millis(),
                jvm.build(), counts);
        Summary summary = summary(span, cpuReport, memoryReport, gcReport, threadReport, exceptionReport, findings);
        return new AnalysisReport(meta, summary, findings, cpuReport, memoryReport, gcReport, threadReport, ioReport,
                exceptionReport);
    }

    private static Summary summary(Span span, CpuReport cpu, MemoryReport memory, GcReport gc, ThreadReport threads,
                                   ExceptionReport exceptions, List<Finding> findings) {
        HotspotReport exec = cpu.execution();
        double parkedMs = threads.park().attributedWeight() / 1e6;
        String topBlocking = topBlocking(threads);
        return new Summary(span.millis(), exec.events(), exec.attributedPercent(), topLine(exec),
                cpu.jvmCpuAvgPercent(), memory.heapMaxBytes(), memory.heapPeakUsedBytes(), memory.liveSetLastBytes(),
                memory.allocatedBytes(), memory.allocationRateBytesPerSec(), topLine(memory.allocations()),
                gc.count(), gc.totalPauseMs(), gc.maxPauseMs(), gc.overheadPercent(),
                threads.monitorEnter().totalWeight() / 1e6, parkedMs, topBlocking, threads.pinned().events(),
                exceptions.throwablesCreated(),
                findings.stream().filter(f -> f.severity() == Severity.CRITICAL).count(),
                findings.stream().filter(f -> f.severity() == Severity.WARNING).count());
    }

    private static @Nullable String topLine(HotspotReport r) {
        return r.hotLines().isEmpty() ? null : r.hotLines().getFirst().location();
    }

    private static @Nullable String topBlocking(ThreadReport threads) {
        Hotspot enter = threads.monitorEnter().hotspots().isEmpty() ? null
                : threads.monitorEnter().hotspots().getFirst();
        Hotspot park = threads.park().hotspots().isEmpty() ? null : threads.park().hotspots().getFirst();
        if (enter == null) {
            return park == null ? null : park.location();
        }
        return park == null || enter.weight() >= park.weight() ? enter.location() : park.location();
    }
}
