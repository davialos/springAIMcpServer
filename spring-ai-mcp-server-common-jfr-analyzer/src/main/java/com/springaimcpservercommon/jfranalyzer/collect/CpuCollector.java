package com.springaimcpservercommon.jfranalyzer.collect;

import com.springaimcpservercommon.jfranalyzer.model.CpuReport;
import jdk.jfr.consumer.RecordedEvent;
import org.jspecify.annotations.Nullable;

/**
 * CPU: {@code jdk.ExecutionSample} (or {@code jdk.CPUTimeSample}), {@code jdk.NativeMethodSample},
 * {@code jdk.CPULoad}.
 */
public final class CpuCollector implements EventCollector {

    private final StackResolver stacks;
    private final HotspotAggregator execution;
    private final HotspotAggregator cpuTime;
    private final HotspotAggregator nativeSamples;
    private final Counter threadStates = new Counter();
    private final TimeSeries samples = new TimeSeries(TimeSeries.Mode.RATE);
    private final TimeSeries packageSamples = new TimeSeries(TimeSeries.Mode.RATE);
    private final TimeSeries cpuTimeSamples = new TimeSeries(TimeSeries.Mode.RATE);
    private final TimeSeries cpuTimePackageSamples = new TimeSeries(TimeSeries.Mode.RATE);
    private final TimeSeries jvmCpu = new TimeSeries(TimeSeries.Mode.AVERAGE);
    private final TimeSeries machineCpu = new TimeSeries(TimeSeries.Mode.AVERAGE);
    private final Limits limits;
    private double jvmSum;
    private double jvmMax;
    private double machineSum;
    private double machineMax;
    private long loadEvents;

    /**
     * @param stacks stack resolver
     * @param limits output limits
     */
    public CpuCollector(StackResolver stacks, Limits limits) {
        this.stacks = stacks;
        this.limits = limits;
        this.execution = new HotspotAggregator("CPU hot spots (execution samples)", "samples", "", limits);
        this.cpuTime = new HotspotAggregator("CPU hot spots (CPU-time samples)", "samples", "", limits);
        this.nativeSamples = new HotspotAggregator("Threads in native code (native method samples)", "samples",
                "", limits);
    }

    @Override
    public void accept(String type, RecordedEvent e) {
        switch (type) {
            case "jdk.ExecutionSample" -> {
                boolean attributed = execution.add(stacks.resolve(e.getStackTrace()), 1,
                        Fields.eventThreadName(e), null);
                String state = Fields.string(e, "state");
                if (state != null) {
                    threadStates.add(state, 1);
                }
                samples.add(e.getStartTime(), 1);
                if (attributed) {
                    packageSamples.add(e.getStartTime(), 1);
                }
            }
            case "jdk.CPUTimeSample" -> {
                if (Boolean.TRUE.equals(e.hasField("failed") ? e.getValue("failed") : null)) {
                    return;
                }
                boolean attributed = cpuTime.add(stacks.resolve(e.getStackTrace()), 1, Fields.eventThreadName(e),
                        null);
                cpuTimeSamples.add(e.getStartTime(), 1);
                if (attributed) {
                    cpuTimePackageSamples.add(e.getStartTime(), 1);
                }
            }
            case "jdk.NativeMethodSample" -> nativeSamples.add(stacks.resolve(e.getStackTrace()), 1,
                    Fields.eventThreadName(e), null);
            case "jdk.CPULoad" -> {
                Double user = Fields.doubleValue(e, "jvmUser");
                Double system = Fields.doubleValue(e, "jvmSystem");
                Double machine = Fields.doubleValue(e, "machineTotal");
                if (user == null || system == null || machine == null) {
                    return;
                }
                double jvm = (user + system) * 100;
                double total = machine * 100;
                jvmCpu.add(e.getStartTime(), jvm);
                machineCpu.add(e.getStartTime(), total);
                jvmSum += jvm;
                jvmMax = Math.max(jvmMax, jvm);
                machineSum += total;
                machineMax = Math.max(machineMax, total);
                loadEvents++;
            }
            default -> {
            }
        }
    }

    /**
     * @param span recording span
     * @return the CPU section
     */
    public CpuReport build(Span span) {
        boolean useCpuTime = execution.events() == 0 && cpuTime.events() > 0;
        HotspotAggregator chosen = useCpuTime ? cpuTime : execution;
        return new CpuReport(useCpuTime ? "jdk.CPUTimeSample" : "jdk.ExecutionSample", chosen.build(),
                nativeSamples.build(), threadStates.top(limits.topN(), execution.totalWeight()),
                avg(jvmSum), max(jvmMax), avg(machineSum), max(machineMax),
                jvmCpu.points(span.start(), span.end()), machineCpu.points(span.start(), span.end()),
                (useCpuTime ? cpuTimeSamples : samples).points(span.start(), span.end()),
                (useCpuTime ? cpuTimePackageSamples : packageSamples).points(span.start(), span.end()));
    }

    private @Nullable Double avg(double sum) {
        return loadEvents == 0 ? null : sum / loadEvents;
    }

    private @Nullable Double max(double value) {
        return loadEvents == 0 ? null : value;
    }
}
