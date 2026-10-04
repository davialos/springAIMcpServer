package com.springaimcpservercommon.jfranalyzer.model;

/** Red/amber/green status of a recording or of one metric, as reported to teams and leadership. */
public enum HealthStatus {
    /** Needs action now. */
    RED,
    /** Worth planning work for. */
    AMBER,
    /** Within the thresholds. */
    GREEN,
    /** Not recorded, so no judgement. */
    UNKNOWN
}
