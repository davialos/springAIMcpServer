package com.springaimcpservercommon.jfranalyzer.model;

import org.jspecify.annotations.Nullable;

/**
 * Exceptions and errors.
 *
 * @param throwablesCreated   throwables created during the recording ({@code jdk.ExceptionStatistics} delta)
 * @param throwablesPerSecond the same per second
 * @param throwSites          {@code jdk.JavaExceptionThrow} / {@code jdk.JavaErrorThrow} by throwing method;
 *                            detail = thrown class. {@code JavaExceptionThrow} is off in the stock settings
 */
public record ExceptionReport(@Nullable Long throwablesCreated, @Nullable Double throwablesPerSecond,
                              HotspotReport throwSites) {
}
