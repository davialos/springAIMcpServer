package com.springaimcpservercommon.jfranalyzer.model;

/**
 * One point of a time series.
 *
 * @param offsetMillis milliseconds since the start of the recording
 * @param value        value in the unit of the series
 */
public record TimePoint(long offsetMillis, double value) {
}
