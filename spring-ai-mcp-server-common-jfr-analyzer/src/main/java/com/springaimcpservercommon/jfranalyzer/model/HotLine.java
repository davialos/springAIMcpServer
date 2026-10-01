package com.springaimcpservercommon.jfranalyzer.model;

/**
 * One source line inside a requested package, ranked by the weight attributed to it.
 *
 * @param location stack-trace style location, {@code pkg.Class.method(Class.java:42)} (clickable in IDE consoles)
 * @param method   the method display name without line
 * @param line     source line number, or {@code -1} when the recording has none
 * @param weight   attributed weight
 * @param count    number of events
 * @param percent  share of the section total (0–100)
 */
public record HotLine(String location, String method, int line, double weight, long count, double percent) {
}
