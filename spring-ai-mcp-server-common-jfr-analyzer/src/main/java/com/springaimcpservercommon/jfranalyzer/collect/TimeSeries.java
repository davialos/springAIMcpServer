package com.springaimcpservercommon.jfranalyzer.collect;

import com.springaimcpservercommon.jfranalyzer.model.TimePoint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Values bucketed per second of wall-clock time, re-bucketed to at most {@link #MAX_POINTS} points on output.
 */
public final class TimeSeries {

    /** Most points a rendered series has. */
    public static final int MAX_POINTS = 240;

    /** How values in one bucket combine. */
    public enum Mode {
        /** Sum, reported per second (a rate). */
        RATE,
        /** Average (a gauge). */
        AVERAGE,
        /** Maximum. */
        MAX
    }

    private final Mode mode;
    private final TreeMap<Long, double[]> seconds = new TreeMap<>();

    /** @param mode how values combine */
    public TimeSeries(Mode mode) {
        this.mode = mode;
    }

    /**
     * @param time  when
     * @param value value
     */
    public void add(Instant time, double value) {
        double[] b = seconds.computeIfAbsent(time.getEpochSecond(), k -> new double[]{0, 0, Double.NEGATIVE_INFINITY});
        b[0] += value;
        b[1]++;
        b[2] = Math.max(b[2], value);
    }

    /** @return whether nothing was added */
    public boolean isEmpty() {
        return seconds.isEmpty();
    }

    /**
     * @param start recording start; offsets are relative to it
     * @param end   recording end
     * @return the series; for {@link Mode#RATE} empty seconds count as zero
     */
    public List<TimePoint> points(Instant start, Instant end) {
        if (seconds.isEmpty()) {
            return List.of();
        }
        long first = start.getEpochSecond();
        long last = Math.max(first, end.getEpochSecond());
        long span = last - first + 1;
        long width = Math.max(1, (span + MAX_POINTS - 1) / MAX_POINTS);
        List<TimePoint> out = new ArrayList<>();
        for (long bucketStart = first; bucketStart <= last; bucketStart += width) {
            Map<Long, double[]> slice = seconds.subMap(bucketStart, bucketStart + width);
            double sum = 0;
            double n = 0;
            double max = Double.NEGATIVE_INFINITY;
            for (double[] b : slice.values()) {
                sum += b[0];
                n += b[1];
                max = Math.max(max, b[2]);
            }
            long seconds = Math.min(width, last - bucketStart + 1);
            long offset = (bucketStart - first) * 1000;
            switch (mode) {
                case RATE -> out.add(new TimePoint(offset, sum / seconds));
                case AVERAGE -> {
                    if (n > 0) {
                        out.add(new TimePoint(offset, sum / n));
                    }
                }
                case MAX -> {
                    if (n > 0) {
                        out.add(new TimePoint(offset, max));
                    }
                }
            }
        }
        return out;
    }
}
