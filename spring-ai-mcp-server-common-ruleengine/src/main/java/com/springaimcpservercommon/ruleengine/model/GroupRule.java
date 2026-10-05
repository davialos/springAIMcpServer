package com.springaimcpservercommon.ruleengine.model;

/**
 * A rule's membership in a group.
 *
 * @param rule     the rule
 * @param sequence evaluation order inside the group (ascending)
 */
public record GroupRule(Rule rule, int sequence) {
}
