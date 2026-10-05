package com.springaimcpservercommon.ruleengine.store;

/** Change-marker scopes of {@code dai_re_change_marker}. */
public enum Scope {
    /** Parameter library (sys objects and attributes). */
    PARAMETERS,
    /** Message bundles. */
    BUNDLES,
    /** Rules, groups, triggers and channel configuration of every tenant. */
    RULES
}
