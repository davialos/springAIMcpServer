package com.springaimcpservercommon.persistence.telemetry;

import com.springaimcpservercommon.core.id.Ids;
import com.springaimcpservercommon.persistence.support.Checks;
import com.springaimcpservercommon.persistence.support.UtcTimes;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * A stateful MCP session ({@code dai_mcp_session}). {@code last_seen_at} is advanced with a bulk update (no version
 * bump, so concurrent requests of one session never conflict); ending the session is a versioned entity update.
 */
@Entity
@Table(name = "dai_mcp_session")
public class McpSession {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "session_id_hash", nullable = false, updatable = false)
    private String sessionIdHash;

    @Column(name = "mcp_client_id", updatable = false)
    private @Nullable UUID mcpClientId;

    @Column(name = "workspace_id", updatable = false)
    private @Nullable UUID workspaceId;

    @Column(name = "principal_id", nullable = false, updatable = false)
    private UUID principalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transport", nullable = false, updatable = false)
    private McpTransport transport;

    @Column(name = "protocol_version", updatable = false)
    private @Nullable String protocolVersion;

    @Column(name = "client_name", updatable = false)
    private @Nullable String clientName;

    @Column(name = "client_version", updatable = false)
    private @Nullable String clientVersion;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "ended_at")
    private @Nullable Instant endedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason")
    private @Nullable McpSessionEndReason endReason;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    /** For JPA only. */
    protected McpSession() {
    }

    /**
     * Opens a session.
     *
     * @param session session data
     * @param now     current time
     * @return a new, unsaved entity with a fresh UUIDv7
     */
    public static McpSession open(NewMcpSession session, Instant now) {
        McpSession s = new McpSession();
        s.id = Ids.newId();
        s.sessionIdHash = Checks.sha256(session.sessionIdHash(), "sessionIdHash");
        s.mcpClientId = session.mcpClientId();
        s.workspaceId = session.workspaceId();
        s.principalId = Checks.required(session.principalId(), "principalId");
        s.transport = Checks.required(session.transport(), "transport");
        s.protocolVersion = Checks.optionalText(session.protocolVersion(), "protocolVersion", 32);
        s.clientName = Checks.optionalText(session.clientName(), "clientName", 256);
        s.clientVersion = Checks.optionalText(session.clientVersion(), "clientVersion", 128);
        s.startedAt = UtcTimes.micros(now);
        s.lastSeenAt = s.startedAt;
        return s;
    }

    /**
     * Ends the session ({@code ck_mcp_session_end}: end time and reason are set together). Ending an ended session
     * keeps the first end.
     *
     * @param reason why it ended
     * @param now    current time
     * @return {@code true} if the session was open and is now ended
     */
    public boolean end(McpSessionEndReason reason, Instant now) {
        Checks.required(reason, "reason");
        if (endedAt != null) {
            return false;
        }
        Instant at = UtcTimes.micros(now);
        endedAt = at.isBefore(startedAt) ? startedAt : at;
        endReason = reason;
        if (lastSeenAt.isBefore(endedAt)) {
            lastSeenAt = endedAt;
        }
        return true;
    }

    /** @return session row id */
    public UUID getId() {
        return id;
    }

    /** @return hash of the session id */
    public String getSessionIdHash() {
        return sessionIdHash;
    }

    /** @return MCP client id, if known */
    public @Nullable UUID getMcpClientId() {
        return mcpClientId;
    }

    /** @return workspace id, if resolved */
    public @Nullable UUID getWorkspaceId() {
        return workspaceId;
    }

    /** @return caller */
    public UUID getPrincipalId() {
        return principalId;
    }

    /** @return transport */
    public McpTransport getTransport() {
        return transport;
    }

    /** @return protocol version, if known */
    public @Nullable String getProtocolVersion() {
        return protocolVersion;
    }

    /** @return client name, if known */
    public @Nullable String getClientName() {
        return clientName;
    }

    /** @return client version, if known */
    public @Nullable String getClientVersion() {
        return clientVersion;
    }

    /** @return start */
    public Instant getStartedAt() {
        return startedAt;
    }

    /** @return last activity */
    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    /** @return end, if ended */
    public @Nullable Instant getEndedAt() {
        return endedAt;
    }

    /** @return end reason, if ended */
    public @Nullable McpSessionEndReason getEndReason() {
        return endReason;
    }

    /** @return optimistic-lock version */
    public long getRowVersion() {
        return rowVersion;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof McpSession other && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "McpSession[" + id + "]";
    }
}
