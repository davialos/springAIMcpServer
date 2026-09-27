package com.springaimcpservercommon.persistence.telemetry;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * A completed model (LLM provider) call to record. {@code dai_model_call} is the single owner of token facts; usage
 * views and the hourly ledger are derived from it.
 *
 * @param id                 call id (UUIDv7)
 * @param startedAt          start
 * @param endedAt            end
 * @param turnId             agent turn; required when the purpose is AGENT_TURN
 * @param seq                position of the call inside its turn, if any
 * @param workspaceId        workspace
 * @param purpose            why the model was called
 * @param provider           provider id, e.g. {@code openai}
 * @param model              model id
 * @param streaming          whether the call streamed
 * @param timeToFirstTokenMs latency to the first token, if streamed
 * @param inputTokens        prompt tokens (≥ 0)
 * @param outputTokens       completion tokens (≥ 0)
 * @param cachedInputTokens  cached prompt tokens (≥ 0)
 * @param costMicros         cost in micro units of {@code currency} (≥ 0)
 * @param currency           ISO 4217 code, required when the cost is not zero
 * @param finishReason       provider finish reason, if any
 * @param outcome            outcome
 * @param errorCode          error code, if any
 * @param providerRequestId  provider request id, if any
 * @param fallbackOfId       call this one is a fallback for, if any
 */
public record NewModelCall(
        UUID id,
        Instant startedAt,
        Instant endedAt,
        @Nullable UUID turnId,
        @Nullable Short seq,
        UUID workspaceId,
        ModelCallPurpose purpose,
        String provider,
        String model,
        boolean streaming,
        @Nullable Integer timeToFirstTokenMs,
        int inputTokens,
        int outputTokens,
        int cachedInputTokens,
        long costMicros,
        @Nullable String currency,
        @Nullable String finishReason,
        ModelCallOutcome outcome,
        @Nullable String errorCode,
        @Nullable String providerRequestId,
        @Nullable UUID fallbackOfId) {
}
