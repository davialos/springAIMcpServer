package com.springaimcpservercommon.ruleengine.channel;

import com.springaimcpservercommon.ruleengine.model.ChannelType;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * What happened to one planned channel.
 *
 * @param bindingId the binding
 * @param type      the channel
 * @param status    outcome
 * @param reason    why not sent (never contains the recipient or payload), or {@code null} when sent
 */
public record DispatchResult(UUID bindingId, ChannelType type, Status status, @Nullable String reason) {

    /** Dispatch status. */
    public enum Status {
        /** Handed to the sender / endpoint answered 2xx. */
        SENT,
        /** Nothing to do: no sender configured, no recipient, or the configuration is incomplete. */
        SKIPPED,
        /** The API environment guard refused the endpoint. */
        REFUSED,
        /** The sender or endpoint failed. */
        FAILED
    }
}
