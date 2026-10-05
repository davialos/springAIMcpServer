package com.springaimcpservercommon.ruleengine.channel;

/** Port: the host's push gateway. */
@FunctionalInterface
public interface PushSender {

    /**
     * Sends the push notification.
     *
     * @param message what to send
     * @throws Exception when sending fails (the dispatcher records FAILED and moves on)
     */
    void send(PushMessage message) throws Exception;
}
