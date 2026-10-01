package com.springaimcpservercommon.jfranalyzer.model;

/** Severity of a {@link Finding}, most severe first. */
public enum Severity {
    /** Likely to hurt users now. */
    CRITICAL,
    /** Worth fixing; costs throughput, latency or memory. */
    WARNING,
    /** Context or a hint about the recording itself. */
    INFO
}
