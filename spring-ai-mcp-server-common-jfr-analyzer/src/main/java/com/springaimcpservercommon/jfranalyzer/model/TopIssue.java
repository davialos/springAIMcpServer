package com.springaimcpservercommon.jfranalyzer.model;

import org.jspecify.annotations.Nullable;

/**
 * A finding restated as impact and next action.
 *
 * @param severity severity
 * @param area     report section ({@code cpu}, {@code memory}, {@code gc}, ...)
 * @param title    what was measured
 * @param impact   why it matters to users of the application
 * @param action   what to do next
 * @param location code location to start from, when one is responsible
 */
public record TopIssue(Severity severity, String area, String title, String impact, String action,
                       @Nullable String location) {
}
