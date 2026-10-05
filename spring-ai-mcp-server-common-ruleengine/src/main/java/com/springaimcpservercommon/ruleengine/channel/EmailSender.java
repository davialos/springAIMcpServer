package com.springaimcpservercommon.ruleengine.channel;

/** Port: the host's mail system. */
@FunctionalInterface
public interface EmailSender {

    /**
     * Sends the e-mail rendered from the caller-side template.
     *
     * @param message what to send
     * @throws Exception when sending fails (the dispatcher records FAILED and moves on)
     */
    void send(EmailMessage message) throws Exception;
}
