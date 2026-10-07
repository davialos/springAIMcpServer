package com.springaimcpservercommon.ruleengine.response;

/** Where a message comes from. */
public enum MessageSource {
    /** A single rule's true/false message. */
    RULE,
    /** The group's composite message. */
    GROUP
}
