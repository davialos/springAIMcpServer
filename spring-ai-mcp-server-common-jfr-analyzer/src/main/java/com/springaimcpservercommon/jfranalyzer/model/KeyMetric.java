package com.springaimcpservercommon.jfranalyzer.model;

import org.jspecify.annotations.Nullable;

/**
 * One headline number with its judgement, phrased for a non-specialist reader.
 *
 * @param id      stable identifier, e.g. {@code gc.overheadPercent}, for tooling that tracks it across runs
 * @param name    display name
 * @param value   the number, in {@code unit}; {@code null} when not recorded
 * @param unit    {@code percent}, {@code ms}, {@code bytes}, {@code bytesPerSecond}, {@code count} or
 *                {@code perSecond}
 * @param display the value formatted for people, e.g. {@code 1.2 GiB/s}
 * @param status  judgement against the thresholds in {@code FindingsEngine}
 * @param meaning one sentence on why it matters
 */
public record KeyMetric(String id, String name, @Nullable Double value, String unit, String display,
                        HealthStatus status, String meaning) {
}
