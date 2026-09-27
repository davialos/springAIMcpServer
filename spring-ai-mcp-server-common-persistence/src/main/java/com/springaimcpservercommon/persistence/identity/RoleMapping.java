package com.springaimcpservercommon.persistence.identity;

import com.springaimcpservercommon.core.id.Ids;
import com.springaimcpservercommon.core.principal.FrameworkRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * IdP claim/authority/group → framework role mapping ({@code dai_role_mapping}, SEC-01 §3). Unique over
 * (source, issuer, claim, value, role, workspace) with NULLs treated as equal.
 */
@Entity
@Table(name = "dai_role_mapping")
public class RoleMapping {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private RoleMappingSource source;

    @Column(name = "issuer")
    private @Nullable String issuer;

    @Column(name = "claim_name")
    private @Nullable String claimName;

    @Column(name = "match_value", nullable = false)
    private String matchValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "framework_role", nullable = false)
    private FrameworkRole role;

    @Column(name = "workspace_id")
    private @Nullable UUID workspaceId;

    @Column(name = "priority", nullable = false)
    private int priority;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "description")
    private @Nullable String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private @Nullable UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private @Nullable UUID updatedBy;

    @Version
    @Column(name = "row_version", nullable = false)
    private @Nullable Long rowVersion;

    /** For JPA only. */
    protected RoleMapping() {
    }

    private RoleMapping(RoleMappingRule rule, @Nullable String description, @Nullable UUID createdBy, Instant now) {
        this.id = Ids.newId();
        apply(rule);
        this.enabled = true;
        this.description = description;
        this.createdAt = now;
        this.createdBy = createdBy;
        this.updatedAt = now;
        this.updatedBy = createdBy;
    }

    /**
     * Creates an enabled mapping.
     *
     * @param rule        validated matching rule
     * @param description optional description
     * @param createdBy   creating principal, {@code null} for bootstrap
     * @param now         creation time
     * @return the new mapping (not yet persisted)
     */
    public static RoleMapping create(RoleMappingRule rule, @Nullable String description, @Nullable UUID createdBy,
                                     Instant now) {
        return new RoleMapping(Objects.requireNonNull(rule, "rule"), description, createdBy,
                Objects.requireNonNull(now, "now"));
    }

    /**
     * Replaces the rule and description.
     *
     * @param rule           validated rule
     * @param newDescription optional description
     * @param by             acting principal
     * @param now            change time
     */
    public void update(RoleMappingRule rule, @Nullable String newDescription, UUID by, Instant now) {
        apply(Objects.requireNonNull(rule, "rule"));
        this.description = newDescription;
        touch(by, now);
    }

    /**
     * Enables or disables the mapping.
     *
     * @param newEnabled target state
     * @param by         acting principal
     * @param now        change time
     */
    public void setEnabled(boolean newEnabled, UUID by, Instant now) {
        if (enabled != newEnabled) {
            this.enabled = newEnabled;
            touch(by, now);
        }
    }

    private void apply(RoleMappingRule rule) {
        this.source = rule.source();
        this.issuer = rule.issuer();
        this.claimName = rule.claimName();
        this.matchValue = rule.matchValue();
        this.role = rule.role();
        this.workspaceId = rule.workspaceId();
        this.priority = rule.priority();
    }

    private void touch(UUID by, Instant now) {
        this.updatedBy = Objects.requireNonNull(by, "by");
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    /**
     * The matching rule of this mapping.
     *
     * @return the rule
     */
    public RoleMappingRule rule() {
        return new RoleMappingRule(source, issuer, claimName, matchValue, role, workspaceId, priority);
    }

    /**
     * Immutable snapshot of this row.
     *
     * @return the view
     */
    public RoleMappingView view() {
        return new RoleMappingView(id, rule(), enabled, description, createdAt, updatedAt, getRowVersion());
    }

    public UUID getId() {
        return id;
    }

    public long getRowVersion() {
        return rowVersion == null ? 0L : rowVersion;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof RoleMapping other && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
