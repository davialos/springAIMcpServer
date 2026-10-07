package com.springaimcpservercommon.ruleengine.domain;

/** How the rules of a group are evaluated and what is reported. */
public enum Policy {
    /** Rules in sequence; stops at the first whose result equals the group's {@code matchOn}; reports that rule. */
    FIRST_MATCH,
    /** Every rule is evaluated; reports all rules whose result equals {@code matchOn}. */
    ALL_MATCH,
    /** Every rule is evaluated; reports every rule's message, true and false. */
    EVALUATE_ALL,
    /** Every rule is evaluated and combined into one result: the group's own message, plus the failing rules'. */
    COMPOSITE
}
