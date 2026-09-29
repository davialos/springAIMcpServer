package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.runtime.TurnRecorder;
import com.springaimcpservercommon.core.id.Ids;
import com.springaimcpservercommon.persistence.telemetry.ModelCallOutcome;
import com.springaimcpservercommon.persistence.telemetry.ModelCallPurpose;
import com.springaimcpservercommon.persistence.telemetry.NewAgentTurn;
import com.springaimcpservercommon.persistence.telemetry.NewModelCall;
import com.springaimcpservercommon.persistence.telemetry.TelemetryStore;
import com.springaimcpservercommon.persistence.telemetry.TurnFinishReason;
import com.springaimcpservercommon.persistence.telemetry.TurnOutcome;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Objects;

/**
 * Writes finished agent turns to the {@code dynamic_ai} store: one {@code dai_agent_turn} row and, when the
 * provider reported tokens, one {@code dai_model_call} row with the priced cost (F-72, LLD-15).
 *
 * <p>Recording is off the request path: each turn is written on a virtual thread (ADR-0007), bounded by a
 * bulkhead of {@value #MAX_IN_FLIGHT} concurrent writes. When the bulkhead is full or a write fails the turn is
 * dropped and counted ({@code dynamic.ai.agent.turn.record.dropped}, {@code ...failures}); telemetry is never
 * allowed to slow or fail a turn or the host (LLD-12). A write can fail when the caller's principal or the
 * workspace is not in the store (foreign keys); that is logged once per hundred failures without any request
 * content.
 *
 * <p>A turn is recorded as a single model call: when a tool loop calls the model several times the provider
 * usage of the final response is what the invoker sees, so tokens are attributed to one call under the agent's
 * configured model. Tool invocations are not recorded here (OQ-43).
 */
@NullMarked
final class StoreTurnRecorder implements TurnRecorder, AutoCloseable {

    static final int MAX_IN_FLIGHT = 64;

    private static final Logger LOG = LoggerFactory.getLogger(StoreTurnRecorder.class);

    private final TelemetryStore store;
    private final ModelCostCalculator costs;
    private final BoundedAsyncWriter writer = new BoundedAsyncWriter(MAX_IN_FLIGHT, "dynamic.ai.agent.turn.record", LOG);

    StoreTurnRecorder(TelemetryStore store, ModelCostCalculator costs) {
        this.store = Objects.requireNonNull(store, "store");
        this.costs = Objects.requireNonNull(costs, "costs");
    }

    @Override
    public void record(TurnRecord turn) {
        writer.submit("Recording turn " + turn.turnId(), () -> {
            write(turn);
            SafeMetrics.count("dynamic.ai.agent.turns.recorded", "outcome", turn.outcome().name());
        });
    }

    private void write(TurnRecord t) {
        store.recordTurn(new NewAgentTurn(t.turnId(), t.startedAt(), t.endedAt(), t.conversationId(),
                t.agent().workspaceId(), t.agent().id(), null, t.principal().principalId(), t.channel(),
                t.traceId(), t.clientRequestId(), TurnFinishReason.valueOf(t.finish().name()),
                TurnOutcome.valueOf(t.outcome().name()), t.errorCode(), t.timeToFirstTokenMs()));
        if (t.inputTokens() + t.outputTokens() > 0) {
            String provider = t.agent().model().providerId();
            String model = t.agent().model().modelName();
            ModelCostCalculator.Cost cost = costs.cost(provider, model, t.inputTokens(), t.outputTokens(), 0L,
                    t.startedAt());
            store.recordModelCall(new NewModelCall(Ids.newId(), t.startedAt(), t.endedAt(), t.turnId(), (short) 0,
                    t.agent().workspaceId(), ModelCallPurpose.AGENT_TURN, provider, model, t.streaming(),
                    t.timeToFirstTokenMs(), clamp(t.inputTokens()), clamp(t.outputTokens()), 0, cost.micros(),
                    cost.currency(), t.finish().name().toLowerCase(Locale.ROOT), modelOutcome(t), t.errorCode(),
                    null, null));
        }
    }

    private static ModelCallOutcome modelOutcome(TurnRecord t) {
        return switch (t.outcome()) {
            case SUCCESS -> ModelCallOutcome.SUCCESS;
            case CANCELLED -> ModelCallOutcome.CANCELLED;
            case FAILED, REJECTED -> isTimeout(t.errorCode()) ? ModelCallOutcome.TIMEOUT : ModelCallOutcome.ERROR;
        };
    }

    private static boolean isTimeout(@Nullable String errorCode) {
        return "turn-timeout".equals(errorCode) || "model-timeout".equals(errorCode);
    }

    private static int clamp(long tokens) {
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, tokens));
    }

    @Override
    public void close() {
        writer.close();
    }
}
