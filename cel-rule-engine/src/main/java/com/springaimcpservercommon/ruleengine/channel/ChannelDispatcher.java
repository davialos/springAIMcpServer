package com.springaimcpservercommon.ruleengine.channel;

import com.springaimcpservercommon.ruleengine.domain.Enums.ChannelType;
import com.springaimcpservercommon.ruleengine.domain.Model.Channel;

/** Communicates an outcome through one kind of channel. */
public interface ChannelDispatcher {

    /**
     * @return the channel type this dispatcher handles
     */
    ChannelType type();

    /**
     * Communicates the notification.
     *
     * @param channel      the configured channel
     * @param notification what to communicate
     * @return what happened; never throws for delivery problems — report them as FAILED or SKIPPED
     */
    Result dispatch(Channel channel, Notification notification);

    /**
     * The outcome of a dispatch.
     *
     * @param status SENT, FAILED or SKIPPED
     * @param detail what happened (never message content)
     */
    record Result(String status, String detail) {

        /**
         * @param detail what happened
         * @return a SENT result
         */
        public static Result sent(String detail) {
            return new Result("SENT", detail);
        }

        /**
         * @param detail why
         * @return a FAILED result
         */
        public static Result failed(String detail) {
            return new Result("FAILED", detail);
        }

        /**
         * @param detail why
         * @return a SKIPPED result
         */
        public static Result skipped(String detail) {
            return new Result("SKIPPED", detail);
        }
    }
}
