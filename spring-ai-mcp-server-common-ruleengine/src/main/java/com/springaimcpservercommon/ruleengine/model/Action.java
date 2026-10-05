package com.springaimcpservercommon.ruleengine.model;

/** What the integrating application should do with the transaction. Ordered by severity. */
public enum Action {
    /** Let the transaction continue. */
    ALLOW,
    /** Let it continue but show the message as a warning. */
    WARN,
    /** Stop the transaction. */
    BLOCK;

    /**
     * The more severe of two actions.
     *
     * @param a first action
     * @param b second action
     * @return BLOCK over WARN over ALLOW
     */
    public static Action strictest(Action a, Action b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
