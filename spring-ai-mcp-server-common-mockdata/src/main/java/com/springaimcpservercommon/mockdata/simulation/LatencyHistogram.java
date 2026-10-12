package com.springaimcpservercommon.mockdata.simulation;

import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free log-linear latency histogram (32 sub-buckets per power of two, about 3 % resolution) in microseconds.
 * Recording is a single {@link LongAdder} increment, so thousands of virtual threads can record concurrently.
 */
final class LatencyHistogram {

    private static final int SUB_BITS = 5;
    private static final int BUCKETS = (41 << SUB_BITS) + 64;

    private final LongAdder[] counts = new LongAdder[BUCKETS];
    private final LongAdder total = new LongAdder();
    private volatile long max;

    LatencyHistogram() {
        for (int i = 0; i < BUCKETS; i++) {
            counts[i] = new LongAdder();
        }
    }

    void record(long micros) {
        long v = Math.max(0, micros);
        counts[index(v)].increment();
        total.increment();
        if (v > max) {
            max = v; // benign race: the maximum only ever grows towards the true value
        }
    }

    long max() {
        return max;
    }

    /** Upper bound of the bucket that holds the given percentile (0-100). */
    long percentile(double p) {
        long n = total.sum();
        if (n == 0) {
            return 0;
        }
        long target = Math.max(1, (long) Math.ceil(p / 100.0 * n));
        long seen = 0;
        for (int i = 0; i < BUCKETS; i++) {
            seen += counts[i].sum();
            if (seen >= target) {
                return Math.min(upper(i), max);
            }
        }
        return max;
    }

    private static int index(long v) {
        int msb = 63 - Long.numberOfLeadingZeros(v | 1);
        int shift = Math.max(0, msb - SUB_BITS);
        return Math.min(BUCKETS - 1, shift == 0 ? (int) v : (shift << SUB_BITS) + (int) (v >> shift));
    }

    private static long upper(int idx) {
        if (idx < 64) {
            return idx;
        }
        int shift = (idx >> SUB_BITS) - 1;
        long sub = idx - ((long) shift << SUB_BITS);
        return ((sub + 1) << shift) - 1;
    }
}
