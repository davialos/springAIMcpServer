package com.springaimcpservercommon.ruleengine.channel;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Where e-mail and push messages leave the engine. The engine does not render templates: an e-mail names the
 * <em>caller-side</em> template (its id and name) and carries the variables; the caller's mail service sends it. The
 * default gateways only record the message; with {@code ruleengine.channels.email-gateway-url} / {@code
 * push-gateway-url} set they POST it as JSON.
 */
public final class Gateways {

    private Gateways() {
    }

    /**
     * An e-mail to send with a caller-side template.
     *
     * @param templateId   the id the caller's mail service knows
     * @param templateName the name shown in the UI
     * @param to           recipients
     * @param language     language of the content
     * @param variables    template variables (messages, action, outcome …)
     */
    public record EmailMessage(String templateId, String templateName, List<String> to, String language,
                               Map<String, Object> variables) { }

    /**
     * A push notification.
     *
     * @param topic recipient topic or device group
     * @param title title
     * @param body  text
     * @param data  extra data
     */
    public record PushMessage(String topic, String title, String body, Map<String, Object> data) { }

    /** Sends e-mails. */
    @FunctionalInterface
    public interface EmailGateway {
        /**
         * @param message the e-mail
         * @return a short description of what happened
         */
        String send(EmailMessage message);
    }

    /** Sends push notifications. */
    @FunctionalInterface
    public interface PushGateway {
        /**
         * @param message the notification
         * @return a short description of what happened
         */
        String send(PushMessage message);
    }

    /**
     * A description of a message, for logs.
     *
     * @param what recipients or topic
     * @return text without message content
     */
    static String describe(@Nullable Object what) {
        return String.valueOf(what);
    }
}
