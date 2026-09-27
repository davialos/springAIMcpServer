package com.springaimcpservercommon.persistence.telemetry;

import com.springaimcpservercommon.core.invocation.Channel;
import com.springaimcpservercommon.persistence.support.Checks;
import com.springaimcpservercommon.persistence.support.UtcTimes;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * One agent turn: a user message through to the final answer ({@code dai_agent_turn}, partitioned monthly by
 * {@code started_at}). Written once when the turn ends; never updated. The database key is
 * {@code (id, started_at)}; a single {@code @Id} is sufficient because rows are never updated or loaded by id alone.
 */
@Entity
@Immutable
@Table(name = "dai_agent_turn")
public class AgentTurn {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "ended_at", nullable = false, updatable = false)
    private Instant endedAt;

    @Column(name = "conversation_id", updatable = false)
    private @Nullable UUID conversationId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "agent_resource_id", updatable = false)
    private @Nullable UUID agentResourceId;

    @Column(name = "agent_revision_id", updatable = false)
    private @Nullable UUID agentRevisionId;

    @Column(name = "principal_id", nullable = false, updatable = false)
    private UUID principalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false)
    private Channel channel;

    @Column(name = "trace_id", updatable = false)
    private @Nullable String traceId;

    @Column(name = "client_request_id", updatable = false)
    private @Nullable String clientRequestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "finish_reason", nullable = false, updatable = false)
    private TurnFinishReason finishReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, updatable = false)
    private TurnOutcome outcome;

    @Column(name = "error_code", updatable = false)
    private @Nullable String errorCode;

    @Column(name = "time_to_first_token_ms", updatable = false)
    private @Nullable Integer timeToFirstTokenMs;

    /** For JPA only. */
    protected AgentTurn() {
    }

    /**
     * Creates a turn row after validating the {@code ck_agent_turn_*} rules.
     *
     * @param turn the completed turn
     * @return a new, unsaved entity
     */
    public static AgentTurn of(NewAgentTurn turn) {
        AgentTurn t = new AgentTurn();
        t.id = Checks.required(turn.id(), "id");
        t.startedAt = UtcTimes.micros(Checks.required(turn.startedAt(), "startedAt"));
        t.endedAt = UtcTimes.micros(Checks.required(turn.endedAt(), "endedAt"));
        Checks.notBefore(t.startedAt, t.endedAt, "agent turn");
        t.conversationId = turn.conversationId();
        t.workspaceId = Checks.required(turn.workspaceId(), "workspaceId");
        t.agentResourceId = turn.agentResourceId();
        t.agentRevisionId = turn.agentRevisionId();
        t.principalId = Checks.required(turn.principalId(), "principalId");
        t.channel = Checks.required(turn.channel(), "channel");
        t.traceId = Checks.optionalText(turn.traceId(), "traceId", 128);
        t.clientRequestId = Checks.optionalText(turn.clientRequestId(), "clientRequestId", 256);
        t.finishReason = Checks.required(turn.finishReason(), "finishReason");
        t.outcome = Checks.required(turn.outcome(), "outcome");
        t.errorCode = Checks.optionalText(turn.errorCode(), "errorCode", 128);
        if (t.outcome != TurnOutcome.SUCCESS && t.errorCode == null) {
            throw new IllegalArgumentException("errorCode is required when the outcome is " + t.outcome);
        }
        Integer ttft = turn.timeToFirstTokenMs();
        if (ttft != null) {
            Checks.nonNegative(ttft, "timeToFirstTokenMs");
        }
        t.timeToFirstTokenMs = ttft;
        return t;
    }

    /** @return turn id */
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

    /** @return conversation id, if any */
    public @Nullable UUID getConversationId() {
        return conversationId;
    }

    /** @return workspace id */
    public UUID getWorkspaceId() {
        return workspaceId;
    }

    /** @return agent resource id, if any */
    public @Nullable UUID getAgentResourceId() {
        return agentResourceId;
    }

    /** @return agent revision id, if any */
    public @Nullable UUID getAgentRevisionId() {
        return agentRevisionId;
    }

    /** @return caller */
    public UUID getPrincipalId() {
        return principalId;
    }

    /** @return entry channel */
    public Channel getChannel() {
        return channel;
    }

    /** @return trace id, if any */
    public @Nullable String getTraceId() {
        return traceId;
    }

    /** @return client request id, if any */
    public @Nullable String getClientRequestId() {
        return clientRequestId;
    }

    /** @return finish reason */
    public TurnFinishReason getFinishReason() {
        return finishReason;
    }

    /** @return outcome */
    public TurnOutcome getOutcome() {
        return outcome;
    }

    /** @return error code, if any */
    public @Nullable String getErrorCode() {
        return errorCode;
    }

    /** @return time to first token in ms, if measured */
    public @Nullable Integer getTimeToFirstTokenMs() {
        return timeToFirstTokenMs;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof AgentTurn other && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "AgentTurn[" + id + "]";
    }
}
