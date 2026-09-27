package com.springaimcpservercommon.persistence.telemetry;

/** What a tool invocation did ({@code ck_tool_invocation_mode}). */
public enum ToolAccessMode {
    /** Executed a read. */
    READ,
    /** Created a write proposal (nothing written, ADR-0009). */
    PROPOSE
}
