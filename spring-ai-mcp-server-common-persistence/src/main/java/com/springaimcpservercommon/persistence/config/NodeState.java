package com.springaimcpservercommon.persistence.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * Per-node applied generation and heartbeat ({@code dai_node_state}, F-73). Read-only mapping: nodes write their
 * row with a native upsert ({@link ConfigStore#heartbeat}).
 */
@Entity
@Immutable
@Table(name = "dai_node_state")
public class NodeState {

    @Id
    @Column(name = "node_id", nullable = false, updatable = false)
    private String nodeId;

    @Column(name = "applied_generation")
    private @Nullable Long appliedGeneration;

    @Column(name = "host_application", nullable = false)
    private String hostApplication;

    @Column(name = "host_version")
    private @Nullable String hostVersion;

    @Column(name = "library_version", nullable = false)
    private String libraryVersion;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "heartbeat_at", nullable = false)
    private Instant heartbeatAt;

    /** For JPA only. */
    protected NodeState() {
    }

    public String getNodeId() {
        return nodeId;
    }

    public @Nullable Long getAppliedGeneration() {
        return appliedGeneration;
    }

    public String getHostApplication() {
        return hostApplication;
    }

    public @Nullable String getHostVersion() {
        return hostVersion;
    }

    public String getLibraryVersion() {
        return libraryVersion;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getHeartbeatAt() {
        return heartbeatAt;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof NodeState other && nodeId.equals(other.getNodeId()));
    }

    @Override
    public int hashCode() {
        return nodeId.hashCode();
    }
}
