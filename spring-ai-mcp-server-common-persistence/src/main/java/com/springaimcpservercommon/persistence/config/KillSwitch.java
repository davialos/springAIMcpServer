package com.springaimcpservercommon.persistence.config;

import com.springaimcpservercommon.core.id.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A kill switch ({@code dai_kill_switch}, F-73). Active while not cleared and not expired. Rows are never deleted
 * (operational history).
 */
@Entity
@Table(name = "dai_kill_switch")
public class KillSwitch {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, updatable = false)
    private KillSwitchTarget.Scope scope;

    @Column(name = "workspace_id", updatable = false)
    private @Nullable UUID workspaceId;

    @Column(name = "resource_id", updatable = false)
    private @Nullable UUID resourceId;

    @Column(name = "tool_name", updatable = false)
    private @Nullable String toolName;

    @Column(name = "reason", nullable = false, updatable = false)
    private String reason;

    @Column(name = "set_by", nullable = false, updatable = false)
    private UUID setBy;

    @Column(name = "set_at", nullable = false, updatable = false)
    private Instant setAt;

    @Column(name = "expires_at", updatable = false)
    private @Nullable Instant expiresAt;

    @Column(name = "cleared_by")
    private @Nullable UUID clearedBy;

    @Column(name = "cleared_at")
    private @Nullable Instant clearedAt;

    /** For JPA only. */
    protected KillSwitch() {
    }

    private KillSwitch(KillSwitchTarget target, String reason, UUID setBy, Instant now, @Nullable Instant expiresAt) {
        this.id = Ids.newId();
        this.scope = target.scope();
        switch (target) {
            case KillSwitchTarget.Global ignored -> {
                // no target columns
            }
            case KillSwitchTarget.Workspace w -> this.workspaceId = w.workspaceId();
            case KillSwitchTarget.Resource r -> {
                this.workspaceId = r.workspaceId();
                this.resourceId = r.resourceId();
            }
            case KillSwitchTarget.Tool t -> {
                this.workspaceId = t.workspaceId();
                this.toolName = t.toolName();
            }
        }
        this.reason = reason;
        this.setBy = setBy;
        this.setAt = now;
        this.expiresAt = expiresAt;
    }

    /**
     * Sets a kill switch.
     *
     * @param target    what to disable
     * @param reason    mandatory reason
     * @param setBy     acting principal
     * @param now       set time
     * @param expiresAt optional automatic expiry, after {@code now}
     * @return the new switch (not yet persisted)
     */
    public static KillSwitch set(KillSwitchTarget target, String reason, UUID setBy, Instant now,
                                 @Nullable Instant expiresAt) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(setBy, "setBy");
        Objects.requireNonNull(now, "now");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("a kill switch needs a reason");
        }
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("kill switch expiry must be in the future");
        }
        return new KillSwitch(target, reason, setBy, now, expiresAt);
    }

    /**
     * Clears the switch. Idempotent.
     *
     * @param by  acting principal
     * @param now clear time
     * @return {@code true} if this call cleared it
     */
    public boolean clear(UUID by, Instant now) {
        if (clearedAt != null) {
            return false;
        }
        this.clearedBy = Objects.requireNonNull(by, "by");
        this.clearedAt = Objects.requireNonNull(now, "now");
        return true;
    }

    /**
     * The target of this switch.
     *
     * @return the target
     */
    public KillSwitchTarget target() {
        return switch (scope) {
            case GLOBAL -> new KillSwitchTarget.Global();
            case WORKSPACE -> new KillSwitchTarget.Workspace(Objects.requireNonNull(workspaceId));
            case RESOURCE -> new KillSwitchTarget.Resource(workspaceId, Objects.requireNonNull(resourceId));
            case TOOL -> new KillSwitchTarget.Tool(workspaceId, Objects.requireNonNull(toolName));
        };
    }

    /**
     * Immutable snapshot of this row.
     *
     * @return the view
     */
    public KillSwitchView view() {
        return new KillSwitchView(id, target(), reason, setBy, setAt, expiresAt, clearedBy, clearedAt);
    }

    public UUID getId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof KillSwitch other && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
