package com.springaimcpservercommon.ruleengine;

import com.springaimcpservercommon.ruleengine.evaluation.GroupResult;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Port: records what was decided (never the input values). A failing recorder never fails an evaluation.
 */
@FunctionalInterface
public interface EvaluationRecorder {

    /** A recorder that records nothing. */
    EvaluationRecorder NONE = (tenantId, organizationId, triggerPointId, language, micros, result) -> { };

    /**
     * Records one group evaluation.
     *
     * @param tenantId       tenant
     * @param organizationId organization, or {@code null}
     * @param triggerPointId trigger that caused it, or {@code null} for a direct group call
     * @param language       language requested by the caller, or {@code null}
     * @param durationMicros evaluation time
     * @param result         the raw result
     */
    void record(UUID tenantId, @Nullable UUID organizationId, @Nullable UUID triggerPointId,
                @Nullable String language, long durationMicros, GroupResult result);
}
