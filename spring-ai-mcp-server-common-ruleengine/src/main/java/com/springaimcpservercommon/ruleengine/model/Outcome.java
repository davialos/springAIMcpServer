package com.springaimcpservercommon.ruleengine.model;

/** Result of evaluating one rule. */
public enum Outcome {
    /** The CEL expression evaluated to true. */
    TRUE,
    /** The CEL expression evaluated to false. */
    FALSE,
    /** The rule could not be evaluated (see {@link com.springaimcpservercommon.ruleengine.evaluation.RuleResult#errorCode()}). */
    ERROR
}
