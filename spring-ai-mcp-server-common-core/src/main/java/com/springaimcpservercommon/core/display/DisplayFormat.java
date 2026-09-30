package com.springaimcpservercommon.core.display;

/** How a client should format a displayed value. The server sends the value unchanged plus this hint. */
public enum DisplayFormat {
    /** Plain text. */
    TEXT,
    /** Number. */
    NUMBER,
    /** {@code true}/{@code false}. */
    BOOLEAN,
    /** ISO-8601 date. */
    DATE,
    /** ISO-8601 date-time. */
    DATETIME
}
