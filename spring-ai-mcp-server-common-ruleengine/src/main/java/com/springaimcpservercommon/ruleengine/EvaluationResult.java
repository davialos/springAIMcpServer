package com.springaimcpservercommon.ruleengine;

import com.springaimcpservercommon.ruleengine.channel.DispatchResult;
import com.springaimcpservercommon.ruleengine.channel.PlannedChannel;
import com.springaimcpservercommon.ruleengine.response.EvaluationResponse;

import java.util.List;

/**
 * Response for the caller plus the communications the evaluation asked for.
 *
 * @param response   what the calling application receives
 * @param planned    communications resolved from the channel bindings (not yet sent)
 * @param dispatched what happened to them; empty unless the evaluation was run with dispatching
 */
public record EvaluationResult(EvaluationResponse response, List<PlannedChannel> planned,
                               List<DispatchResult> dispatched) {

    /** Defensive copies. */
    public EvaluationResult {
        planned = List.copyOf(planned);
        dispatched = List.copyOf(dispatched);
    }
}
