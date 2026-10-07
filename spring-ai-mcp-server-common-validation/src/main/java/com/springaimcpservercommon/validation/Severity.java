package com.springaimcpservercommon.validation;

/** How serious a violation is; only {@link #ERROR} makes a result invalid. */
public enum Severity {
    /** Blocks the operation. */
    ERROR,
    /** Reported, does not block. */
    WARNING
}
