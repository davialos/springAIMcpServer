package com.springaimcpservercommon.jfranalyzer.collect;

/**
 * Output size limits.
 *
 * @param topN             rows per ranked list
 * @param stacksPerHotspot call paths kept per hot spot
 * @param stackDepth       frames kept per call path
 */
public record Limits(int topN, int stacksPerHotspot, int stackDepth) {

    /** @return rows for the nested lists of a hot spot (lines, callees, details, threads) */
    public int nested() {
        return Math.max(3, Math.min(10, topN));
    }
}
