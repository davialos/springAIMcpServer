package com.springaimcpservercommon.jfranalyzer.model;

import java.util.List;

/**
 * A distinct call path (leaf first, cut at the configured depth) and the weight that went through it.
 *
 * @param weight    weight of the events with this path
 * @param count     number of events
 * @param percent   share of the section total (0–100)
 * @param frames    frames, leaf first
 * @param truncated whether the path was cut (by JFR's stack depth or the analyzer's)
 */
public record StackPath(double weight, long count, double percent, List<StackFrameView> frames, boolean truncated) {
}
