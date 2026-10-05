package com.springaimcpservercommon.ruleengine;

/** No active rule group with that module and code exists for the tenant. */
public final class UnknownRuleGroupException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param moduleCode module
     * @param groupCode  group
     */
    public UnknownRuleGroupException(String moduleCode, String groupCode) {
        super("no active rule group " + moduleCode + "/" + groupCode);
    }
}
