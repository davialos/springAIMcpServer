package com.springaimcpservercommon.ruleengine.response;

/** How much the caller wants back. */
public enum ResponseDetail {
    /** Decision and localized messages only. */
    MESSAGES,
    /** Additionally the raw per-rule results (outcome, action, error code). */
    WITH_RAW
}
