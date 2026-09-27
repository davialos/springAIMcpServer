package com.springaimcpservercommon.persistence.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * Head of an audit hash chain ({@code dai_audit_chain}). Read-only for JPA: {@link AuditTrail} creates and advances
 * heads with native SQL under {@code SELECT … FOR UPDATE}, which serialises appends within one chain. The head can
 * be exported to WORM storage for tamper evidence beyond the database (LLD-10 §4.1).
 */
@Entity
@Immutable
@Table(name = "dai_audit_chain")
public class AuditChain {

    @Id
    @Column(name = "chain_id", nullable = false, updatable = false)
    private String chainId;

    @Column(name = "last_seq", nullable = false)
    private long lastSeq;

    @Column(name = "last_hash", nullable = false)
    private String lastHash;

    @Column(name = "last_event_at")
    private @Nullable Instant lastEventAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** For JPA only. */
    protected AuditChain() {
    }

    /** @return chain id (workspace id text or {@code system}) */
    public String getChainId() {
        return chainId;
    }

    /** @return sequence number of the last event (0 = empty chain) */
    public long getLastSeq() {
        return lastSeq;
    }

    /** @return hash of the last event (genesis hash for an empty chain) */
    public String getLastHash() {
        return lastHash;
    }

    /** @return time of the last event, if any */
    public @Nullable Instant getLastEventAt() {
        return lastEventAt;
    }

    /** @return creation time */
    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof AuditChain other && chainId.equals(other.getChainId()));
    }

    @Override
    public int hashCode() {
        return chainId.hashCode();
    }
}
