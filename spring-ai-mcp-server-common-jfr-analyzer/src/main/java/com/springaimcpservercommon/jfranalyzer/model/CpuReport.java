package com.springaimcpservercommon.jfranalyzer.model;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * CPU: where Java threads were running.
 *
 * @param sampleSource      event type the samples came from ({@code jdk.ExecutionSample}, or
 *                          {@code jdk.CPUTimeSample} when only that was recorded)
 * @param execution         attribution of execution samples
 * @param nativeSamples     attribution of {@code jdk.NativeMethodSample} (threads inside native code, typically
 *                          waiting on I/O)
 * @param threadStates      sampled thread states
 * @param jvmCpuAvgPercent  average JVM CPU (user + system) as a share of the machine, 0–100
 * @param jvmCpuMaxPercent  maximum of the same
 * @param machineCpuAvgPercent average machine CPU, 0–100
 * @param machineCpuMaxPercent maximum machine CPU, 0–100
 * @param jvmCpuTimeline    JVM CPU % over time
 * @param machineCpuTimeline machine CPU % over time
 * @param sampleTimeline    execution samples per second over time, all threads
 * @param packageSampleTimeline execution samples per second attributed to the packages
 */
public record CpuReport(String sampleSource, HotspotReport execution, HotspotReport nativeSamples,
                        List<WeightedName> threadStates,
                        @Nullable Double jvmCpuAvgPercent, @Nullable Double jvmCpuMaxPercent,
                        @Nullable Double machineCpuAvgPercent, @Nullable Double machineCpuMaxPercent,
                        List<TimePoint> jvmCpuTimeline, List<TimePoint> machineCpuTimeline,
                        List<TimePoint> sampleTimeline, List<TimePoint> packageSampleTimeline) {
}
