package com.springaimcpservercommon.ruleengine.channel;

/**
 * A push notification, already localized.
 *
 * @param recipient device or user id from the recipient expression (never logged)
 * @param title     localized title, possibly empty
 * @param body      localized body
 * @param language  language of the texts
 */
public record PushMessage(String recipient, String title, String body, String language) {
}
