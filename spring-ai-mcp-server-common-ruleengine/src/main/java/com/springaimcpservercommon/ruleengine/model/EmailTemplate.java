package com.springaimcpservercommon.ruleengine.model;

/**
 * A caller-side e-mail template, shown in the UI by id and name.
 *
 * @param templateRef the id the caller's mail system knows the template by
 * @param name        display name
 */
public record EmailTemplate(String templateRef, String name) {
}
