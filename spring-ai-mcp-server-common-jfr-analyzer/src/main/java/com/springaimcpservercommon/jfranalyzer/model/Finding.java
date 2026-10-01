package com.springaimcpservercommon.jfranalyzer.model;

import org.jspecify.annotations.Nullable;

/**
 * A conclusion drawn from the numbers, with the code location to look at when there is one.
 *
 * @param severity severity
 * @param category report section it belongs to ({@code cpu}, {@code memory}, {@code gc}, {@code threads},
 *                 {@code io}, {@code exceptions}, {@code recording})
 * @param title    one-line statement
 * @param detail   explanation and what to do
 * @param location stack-trace style location in a requested package, when one is responsible
 */
public record Finding(Severity severity, String category, String title, String detail, @Nullable String location) {
}
