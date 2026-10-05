package com.springaimcpservercommon.ruleengine.model;

/** Communication channel. */
public enum ChannelType {
    /** E-mail from a caller-side template. */
    EMAIL,
    /** Push notification. */
    PUSH,
    /** Call to an HTTP API. */
    API
}
