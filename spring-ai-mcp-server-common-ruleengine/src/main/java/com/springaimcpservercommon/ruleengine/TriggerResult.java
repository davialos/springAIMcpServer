package com.springaimcpservercommon.ruleengine;

import com.springaimcpservercommon.ruleengine.model.Action;

import java.util.List;

/**
 * Result of a trigger point that may run several rule groups.
 *
 * @param decision the strictest decision of all groups (ALLOW when no group is bound to the trigger)
 * @param groups   one result per bound group, in trigger sequence order
 */
public record TriggerResult(Action decision, List<EvaluationResult> groups) {

    /** Defensive copy. */
    public TriggerResult {
        groups = List.copyOf(groups);
    }
}
