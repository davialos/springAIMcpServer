package com.springaimcpservercommon.ruleengine.channel;

import com.springaimcpservercommon.ruleengine.response.EvaluationResponse;

import java.util.List;
import java.util.UUID;

/**
 * How planned communications leave the engine: sent directly ({@link ChannelDispatcher}) or stored in the delivery
 * outbox for a worker to send with retries ({@link OutboxChannelDelivery}).
 */
public interface ChannelDelivery {

    /**
     * Delivers (or queues) every planned channel. Never throws for a single channel's failure.
     *
     * @param tenantId tenant of the evaluation
     * @param planned  what the evaluation asked for
     * @param response the response the caller gets (decision and messages go into API payloads)
     * @return one result per planned channel, in order
     */
    List<DispatchResult> dispatch(UUID tenantId, List<PlannedChannel> planned, EvaluationResponse response);
}
