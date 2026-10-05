package com.springaimcpservercommon.ruleengine.model;

/** How the rules of a group are evaluated and which results are reported. */
public enum EvaluationPolicy {
    /** Rules run in sequence order and evaluation stops at the first rule whose result equals the group's match-on value. */
    FIRST_MATCH,
    /** Every rule runs; the rules whose result equals the match-on value are reported. */
    ALL_MATCH,
    /** Every rule runs and every result, true and false, is reported. */
    EVALUATE_ALL,
    /** Every rule runs; all must be true. One group message (true or false) is reported, plus the failing rules' messages. */
    COMPOSITE
}
