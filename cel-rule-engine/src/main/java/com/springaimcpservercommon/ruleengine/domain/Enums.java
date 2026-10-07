package com.springaimcpservercommon.ruleengine.domain;

/** Small enumerations of the model. */
public final class Enums {

    private Enums() {
    }

    /** Lifecycle of a rule or group. Only ACTIVE ones are evaluated. */
    public enum Status { DRAFT, ACTIVE, INACTIVE }

    /** How a COMPOSITE group combines its rules. */
    public enum CompositeMode {
        /** True when every rule is true. */
        ALL_TRUE,
        /** True when at least one rule is true. */
        ANY_TRUE
    }

    /** What a rule that cannot be evaluated (missing attribute, type error) counts as. */
    public enum OnError {
        /** As false (fail-safe). */
        AS_FALSE,
        /** It is left out. */
        SKIP
    }

    /** Channel kinds. */
    public enum ChannelType { EMAIL, PUSH, API }

    /** The outcome an action binding reacts to. */
    public enum Outcome { TRUE, FALSE }

    /** Kinds of trigger point; only forms for now. */
    public enum TriggerType { FORM }
}
