package com.springaimcpservercommon.persistence.telemetry;

import com.springaimcpservercommon.persistence.support.Checks;
import com.springaimcpservercommon.persistence.support.UtcTimes;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One call to an LLM provider with tokens, latency, cost and outcome ({@code dai_model_call}, partitioned monthly by
 * {@code started_at}). Insert-only.
 */
@Entity
@Immutable
@Table(name = "dai_model_call")
public class ModelCall {

    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "ended_at", nullable = false, updatable = false)
    private Instant endedAt;

    @Column(name = "turn_id", updatable = false)
    private @Nullable UUID turnId;

    @Column(name = "seq", updatable = false)
    private @Nullable Short seq;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, updatable = false)
    private ModelCallPurpose purpose;

    @Column(name = "provider", nullable = false, updatable = false)
    private String provider;

    @Column(name = "model", nullable = false, updatable = false)
    private String model;

    @Column(name = "streaming", nullable = false, updatable = false)
    private boolean streaming;

    @Column(name = "time_to_first_token_ms", updatable = false)
    private @Nullable Integer timeToFirstTokenMs;

    @Column(name = "input_tokens", nullable = false, updatable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false, updatable = false)
    private int outputTokens;

    @Column(name = "cached_input_tokens", nullable = false, updatable = false)
    private int cachedInputTokens;

    @Column(name = "cost_micros", nullable = false, updatable = false)
    private long costMicros;

    /** {@code char(3)} in the schema, hence the explicit CHAR JDBC type (schema validation compares type codes). */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", length = 3, updatable = false)
    private @Nullable String currency;

    @Column(name = "finish_reason", updatable = false)
    private @Nullable String finishReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, updatable = false)
    private ModelCallOutcome outcome;

    @Column(name = "error_code", updatable = false)
    private @Nullable String errorCode;

    @Column(name = "provider_request_id", updatable = false)
    private @Nullable String providerRequestId;

    @Column(name = "fallback_of_id", updatable = false)
    private @Nullable UUID fallbackOfId;

    /** For JPA only. */
    protected ModelCall() {
    }

    /**
     * Creates a model-call row after validating the {@code ck_model_call_*} rules.
     *
     * @param call the completed call
     * @return a new, unsaved entity
     */
    public static ModelCall of(NewModelCall call) {
        ModelCall c = new ModelCall();
        c.id = Checks.required(call.id(), "id");
        c.startedAt = UtcTimes.micros(Checks.required(call.startedAt(), "startedAt"));
        c.endedAt = UtcTimes.micros(Checks.required(call.endedAt(), "endedAt"));
        Checks.notBefore(c.startedAt, c.endedAt, "model call");
        c.purpose = Checks.required(call.purpose(), "purpose");
        c.turnId = call.turnId();
        if (c.purpose == ModelCallPurpose.AGENT_TURN && c.turnId == null) {
            throw new IllegalArgumentException("turnId is required for AGENT_TURN model calls");
        }
        Short seq = call.seq();
        if (seq != null) {
            Checks.nonNegative(seq, "seq");
        }
        c.seq = seq;
        c.workspaceId = Checks.required(call.workspaceId(), "workspaceId");
        c.provider = Checks.text(call.provider(), "provider", 64);
        c.model = Checks.text(call.model(), "model", 256);
        c.streaming = call.streaming();
        Integer ttft = call.timeToFirstTokenMs();
        if (ttft != null) {
            Checks.nonNegative(ttft, "timeToFirstTokenMs");
        }
        c.timeToFirstTokenMs = ttft;
        c.inputTokens = (int) Checks.nonNegative(call.inputTokens(), "inputTokens");
        c.outputTokens = (int) Checks.nonNegative(call.outputTokens(), "outputTokens");
        c.cachedInputTokens = (int) Checks.nonNegative(call.cachedInputTokens(), "cachedInputTokens");
        c.costMicros = Checks.nonNegative(call.costMicros(), "costMicros");
        c.currency = Checks.optionalMatches(call.currency(), CURRENCY, "currency");
        if (c.costMicros > 0 && c.currency == null) {
            throw new IllegalArgumentException("currency is required when costMicros > 0");
        }
        c.finishReason = Checks.optionalText(call.finishReason(), "finishReason", 64);
        c.outcome = Checks.required(call.outcome(), "outcome");
        c.errorCode = Checks.optionalText(call.errorCode(), "errorCode", 128);
        c.providerRequestId = Checks.optionalText(call.providerRequestId(), "providerRequestId", 256);
        c.fallbackOfId = call.fallbackOfId();
        return c;
    }

    /** @return call id */
    public UUID getId() {
        return id;
    }

    /** @return start */
    public Instant getStartedAt() {
        return startedAt;
    }

    /** @return end */
    public Instant getEndedAt() {
        return endedAt;
    }

    /** @return turn id, if any */
    public @Nullable UUID getTurnId() {
        return turnId;
    }

    /** @return position in the turn, if any */
    public @Nullable Short getSeq() {
        return seq;
    }

    /** @return workspace id */
    public UUID getWorkspaceId() {
        return workspaceId;
    }

    /** @return purpose */
    public ModelCallPurpose getPurpose() {
        return purpose;
    }

    /** @return provider id */
    public String getProvider() {
        return provider;
    }

    /** @return model id */
    public String getModel() {
        return model;
    }

    /** @return whether the call streamed */
    public boolean isStreaming() {
        return streaming;
    }

    /** @return time to first token in ms, if measured */
    public @Nullable Integer getTimeToFirstTokenMs() {
        return timeToFirstTokenMs;
    }

    /** @return prompt tokens */
    public int getInputTokens() {
        return inputTokens;
    }

    /** @return completion tokens */
    public int getOutputTokens() {
        return outputTokens;
    }

    /** @return cached prompt tokens */
    public int getCachedInputTokens() {
        return cachedInputTokens;
    }

    /** @return cost in micro units */
    public long getCostMicros() {
        return costMicros;
    }

    /** @return ISO 4217 currency, if any */
    public @Nullable String getCurrency() {
        return currency;
    }

    /** @return provider finish reason, if any */
    public @Nullable String getFinishReason() {
        return finishReason;
    }

    /** @return outcome */
    public ModelCallOutcome getOutcome() {
        return outcome;
    }

    /** @return error code, if any */
    public @Nullable String getErrorCode() {
        return errorCode;
    }

    /** @return provider request id, if any */
    public @Nullable String getProviderRequestId() {
        return providerRequestId;
    }

    /** @return id of the call this one replaced as fallback, if any */
    public @Nullable UUID getFallbackOfId() {
        return fallbackOfId;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof ModelCall other && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "ModelCall[" + id + "]";
    }
}
