package com.springaimcpservercommon.ruleengine.channel;

import com.springaimcpservercommon.ruleengine.model.Action;

/**
 * An e-mail to send from a caller-side template.
 *
 * @param templateRef  the template id in the caller's mail system
 * @param templateName display name of the template
 * @param recipient    address from the recipient expression (personal data: never logged)
 * @param language     preferred language of the end user
 * @param moduleCode   module of the group
 * @param groupCode    group that produced it
 * @param decision     the group's decision
 */
public record EmailMessage(String templateRef, String templateName, String recipient, String language,
                           String moduleCode, String groupCode, Action decision) {
}
