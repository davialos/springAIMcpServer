package com.springaimcpservercommon.persistence.audit;

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

/**
 * One hash-chained audit event ({@code dai_audit_event}, partitioned monthly by {@code occurred_at}, append-only by
 * trigger). {@code hash = sha256(canonical form)} as specified by {@link AuditCanonicalForm}; the canonical form
 * contains {@code prev_hash}, so every event commits to the whole chain before it.
 */
@Entity
@Immutable
@Table(name = "dai_audit_event")
public class AuditEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "chain_id", nullable = false, updatable = false)
    private String chainId;

    @Column(name = "chain_seq", nullable = false, updatable = false)
    private long chainSeq;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, updatable = false)
    private AuditCategory category;

    @Column(name = "action", nullable = false, updatable = false)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(name = "plane", nullable = false, updatable = false)
    private AuditPlane plane;

    @Column(name = "actor_id", updatable = false)
    private @Nullable UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, updatable = false)
    private AuditActorType actorType;

    @Column(name = "on_behalf_of_id", updatable = false)
    private @Nullable UUID onBehalfOfId;

    @Column(name = "workspace_id", updatable = false)
    private @Nullable UUID workspaceId;

    @Column(name = "resource_type", updatable = false)
    private @Nullable String resourceType;

    @Column(name = "resource_id", updatable = false)
    private @Nullable String resourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, updatable = false)
    private AuditDecision decision;

    @Column(name = "reason", updatable = false)
    private @Nullable String reason;

    @Column(name = "environment_id", nullable = false, updatable = false)
    private String environmentId;

    @Column(name = "trace_id", updatable = false)
    private @Nullable String traceId;

    @Column(name = "turn_id", updatable = false)
    private @Nullable UUID turnId;

    @Column(name = "tool_invocation_id", updatable = false)
    private @Nullable UUID toolInvocationId;

    @Column(name = "proposal_id", updatable = false)
    private @Nullable UUID proposalId;

    @Column(name = "mcp_session_id", updatable = false)
    private @Nullable UUID mcpSessionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", updatable = false)
    private @Nullable String details;

    @Column(name = "prev_hash", nullable = false, updatable = false)
    private String prevHash;

    @Column(name = "hash", nullable = false, updatable = false)
    private String hash;

    /** For JPA only. */
    protected AuditEvent() {
    }

    /**
     * Builds the next event of a chain and computes its hash.
     *
     * @param draft         validated draft
     * @param id            event id
     * @param occurredAt    event time (truncated to microseconds)
     * @param chainId       chain
     * @param chainSeq      sequence number in the chain (≥ 1)
     * @param environmentId environment of the store
     * @param prevHash      hash of the previous event (or the chain's genesis hash)
     * @return a new, unsaved event
     */
    static AuditEvent chained(AuditEventDraft draft, UUID id, Instant occurredAt, String chainId, long chainSeq,
                              String environmentId, String prevHash) {
        if (chainSeq < 1) {
            throw new IllegalArgumentException("chainSeq must be positive");
        }
        AuditEvent e = new AuditEvent();
        e.id = Checks.required(id, "id");
        e.occurredAt = UtcTimes.micros(occurredAt);
        e.chainId = Checks.matches(chainId, AuditTrail.CHAIN_ID, "chainId");
        e.chainSeq = chainSeq;
        e.category = draft.category();
        e.action = draft.action();
        e.plane = draft.plane();
        e.actorId = draft.actorId();
        e.actorType = draft.actorType();
        e.onBehalfOfId = draft.onBehalfOfId();
        e.workspaceId = draft.workspaceId();
        e.resourceType = draft.resourceType();
        e.resourceId = draft.resourceId();
        e.decision = draft.decision();
        e.reason = draft.reason();
        e.environmentId = Checks.text(environmentId, "environmentId", 128);
        e.traceId = draft.traceId();
        e.turnId = draft.turnId();
        e.toolInvocationId = draft.toolInvocationId();
        e.proposalId = draft.proposalId();
        e.mcpSessionId = draft.mcpSessionId();
        e.details = draft.detailsJson();
        e.prevHash = Checks.sha256(prevHash, "prevHash");
        e.hash = AuditCanonicalForm.hash(e);
        return e;
    }

    /** @return event id */
    public UUID getId() {
        return id;
    }

    /** @return event time */
    public Instant getOccurredAt() {
        return occurredAt;
    }

    /** @return chain id */
    public String getChainId() {
        return chainId;
    }

    /** @return sequence number in the chain */
    public long getChainSeq() {
        return chainSeq;
    }

    /** @return category */
    public AuditCategory getCategory() {
        return category;
    }

    /** @return action code */
    public String getAction() {
        return action;
    }

    /** @return plane */
    public AuditPlane getPlane() {
        return plane;
    }

    /** @return actor id, {@code null} for SYSTEM */
    public @Nullable UUID getActorId() {
        return actorId;
    }

    /** @return actor type */
    public AuditActorType getActorType() {
        return actorType;
    }

    /** @return principal acted for, if any */
    public @Nullable UUID getOnBehalfOfId() {
        return onBehalfOfId;
    }

    /** @return workspace id, if any */
    public @Nullable UUID getWorkspaceId() {
        return workspaceId;
    }

    /** @return resource type, if any */
    public @Nullable String getResourceType() {
        return resourceType;
    }

    /** @return resource id, if any */
    public @Nullable String getResourceId() {
        return resourceId;
    }

    /** @return decision */
    public AuditDecision getDecision() {
        return decision;
    }

    /** @return reason, if any */
    public @Nullable String getReason() {
        return reason;
    }

    /** @return environment id */
    public String getEnvironmentId() {
        return environmentId;
    }

    /** @return trace id, if any */
    public @Nullable String getTraceId() {
        return traceId;
    }

    /** @return agent turn id, if any */
    public @Nullable UUID getTurnId() {
        return turnId;
    }

    /** @return tool invocation id, if any */
    public @Nullable UUID getToolInvocationId() {
        return toolInvocationId;
    }

    /** @return proposal id, if any */
    public @Nullable UUID getProposalId() {
        return proposalId;
    }

    /** @return MCP session id, if any */
    public @Nullable UUID getMcpSessionId() {
        return mcpSessionId;
    }

    /** @return details JSON object, if any (as stored; re-canonicalised for hashing) */
    public @Nullable String getDetailsJson() {
        return details;
    }

    /** @return hash of the previous event (or genesis) */
    public String getPrevHash() {
        return prevHash;
    }

    /** @return this event's hash */
    public String getHash() {
        return hash;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof AuditEvent other && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "AuditEvent[" + chainId + "#" + chainSeq + ", " + action + "]";
    }
}
