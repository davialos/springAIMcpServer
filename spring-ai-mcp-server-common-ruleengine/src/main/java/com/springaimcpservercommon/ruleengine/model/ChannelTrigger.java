package com.springaimcpservercommon.ruleengine.model;

/** The outcome a channel reacts to. */
public enum ChannelTrigger {
    /** Result true. */
    TRUE,
    /** Result false. */
    FALSE,
    /** Evaluation error. */
    ERROR,
    /** Any result. */
    ANY;

    /**
     * Whether a channel bound to this trigger fires for an outcome.
     *
     * @param outcome the rule/group outcome
     * @return {@code true} if it fires
     */
    public boolean fires(Outcome outcome) {
        return this == ANY || name().equals(outcome.name());
    }
}
