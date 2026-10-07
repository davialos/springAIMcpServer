package com.springaimcpservercommon.ruleengine.domain;

/** What the calling application should do with the transaction; ordered by severity. */
public enum Action {
    /** Carry on. */
    ALLOW,
    /** Carry on, but show the message. */
    WARN,
    /** Stop the transaction. */
    BLOCK;

    /**
     * The more severe of two actions.
     *
     * @param other the other action
     * @return the one that restricts more
     */
    public Action max(Action other) {
        return other.ordinal() > ordinal() ? other : this;
    }
}
