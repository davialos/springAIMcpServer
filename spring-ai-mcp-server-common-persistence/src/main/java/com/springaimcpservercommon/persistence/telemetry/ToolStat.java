package com.springaimcpservercommon.persistence.telemetry;

/**
 * Aggregate of one tool's invocations in a time window.
 *
 * @param toolName        tool
 * @param calls           invocations
 * @param errors          invocations that ended ERROR, TIMEOUT or UNAVAILABLE
 * @param notPermitted    invocations refused by the permission re-check
 * @param writeViolations invocations the AI write guard vetoed
 * @param avgMillis       mean duration in milliseconds
 * @param maxMillis       longest duration in milliseconds
 */
public record ToolStat(String toolName, long calls, long errors, long notPermitted, long writeViolations,
                       double avgMillis, double maxMillis) {
}
