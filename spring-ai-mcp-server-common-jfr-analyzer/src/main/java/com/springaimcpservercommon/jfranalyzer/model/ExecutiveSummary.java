package com.springaimcpservercommon.jfranalyzer.model;

import java.util.List;

/**
 * The one-screen view of a recording for teams and leadership: an overall status and score, the numbers that
 * matter, the issues worth acting on and the order to act in.
 *
 * @param status          overall status: RED with any critical finding or a score under 50, AMBER with any warning or
 *                        a score under 80, else GREEN
 * @param healthScore     0–100: 100 minus 25 per critical and 8 per warning finding
 * @param headline        one sentence
 * @param keyMetrics      headline numbers with their status
 * @param topIssues       critical and warning findings as impact and action, most severe first
 * @param recommendations ordered next steps
 */
public record ExecutiveSummary(HealthStatus status, int healthScore, String headline, List<KeyMetric> keyMetrics,
                               List<TopIssue> topIssues, List<String> recommendations) {
}
