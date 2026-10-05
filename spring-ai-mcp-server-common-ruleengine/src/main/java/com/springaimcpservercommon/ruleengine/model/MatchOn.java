package com.springaimcpservercommon.ruleengine.model;

/** The rule result a FIRST_MATCH / ALL_MATCH policy selects on. */
public enum MatchOn {
    /** Select rules that evaluated to true. */
    TRUE,
    /** Select rules that evaluated to false (an evaluation error counts as a failure). */
    FALSE
}
