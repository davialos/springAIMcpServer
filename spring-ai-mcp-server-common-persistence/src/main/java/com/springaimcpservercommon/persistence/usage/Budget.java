package com.springaimcpservercommon.persistence.usage;

import com.springaimcpservercommon.core.id.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A budget ({@code dai_budget}, F-70). Unique per (scope, workspace, agent, principal, period) with NULLs treated
 * as equal, so there is at most one GLOBAL daily budget.
 */
@Entity
@Table(name = "dai_budget")
public class Budget {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, updatable = false)
    private BudgetTarget.Scope scope;

    @Column(name = "workspace_id", updatable = false)
    private @Nullable UUID workspaceId;

    @Column(name = "agent_resource_id", updatable = false)
    private @Nullable UUID agentResourceId;

    @Column(name = "principal_id", updatable = false)
    private @Nullable UUID principalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "period", nullable = false, updatable = false)
    private BudgetPeriod period;

    @Column(name = "limit_tokens")
    private @Nullable Long limitTokens;

    @Column(name = "limit_cost_micros")
    private @Nullable Long limitCostMicros;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", length = 3)
    private @Nullable String currency;

    @Column(name = "soft_limit_pct", nullable = false)
    private short softLimitPct;

    @Column(name = "hard_limit", nullable = false)
    private boolean hardLimit;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

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
    protected Budget() {
    }

    private Budget(BudgetTarget target, BudgetPeriod period, BudgetLimits limits, @Nullable UUID createdBy,
                   Instant now) {
        this.id = Ids.newId();
        this.scope = target.scope();
        switch (target) {
            case BudgetTarget.Global ignored -> {
                // no scope columns
            }
            case BudgetTarget.Workspace w -> this.workspaceId = w.workspaceId();
            case BudgetTarget.Agent a -> {
                this.workspaceId = a.workspaceId();
                this.agentResourceId = a.agentResourceId();
            }
            case BudgetTarget.Principal p -> {
                this.workspaceId = p.workspaceId();
                this.principalId = p.principalId();
            }
        }
        this.period = period;
        apply(limits);
        this.enabled = true;
        this.createdAt = now;
        this.createdBy = createdBy;
        this.updatedAt = now;
        this.updatedBy = createdBy;
    }

    /**
     * Creates an enabled budget.
     *
     * @param target    whose usage is limited
     * @param period    DAY or MONTH (UTC)
     * @param limits    validated limits
     * @param createdBy creating principal
     * @param now       creation time
     * @return the new budget (not yet persisted)
     */
    public static Budget create(BudgetTarget target, BudgetPeriod period, BudgetLimits limits,
                                @Nullable UUID createdBy, Instant now) {
        return new Budget(Objects.requireNonNull(target, "target"), Objects.requireNonNull(period, "period"),
                Objects.requireNonNull(limits, "limits"), createdBy, Objects.requireNonNull(now, "now"));
    }

    /**
     * Replaces the limits.
     *
     * @param limits validated limits
     * @param by     acting principal
     * @param now    change time
     */
    public void changeLimits(BudgetLimits limits, UUID by, Instant now) {
        apply(Objects.requireNonNull(limits, "limits"));
        touch(by, now);
    }

    /**
     * Enables or disables the budget.
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

    private void apply(BudgetLimits limits) {
        this.limitTokens = limits.limitTokens();
        this.limitCostMicros = limits.limitCostMicros();
        this.currency = limits.currency();
        this.softLimitPct = (short) limits.softLimitPct();
        this.hardLimit = limits.hardLimit();
    }

    private void touch(UUID by, Instant now) {
        this.updatedBy = Objects.requireNonNull(by, "by");
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    /**
     * The target of this budget.
     *
     * @return the target
     */
    public BudgetTarget target() {
        return BudgetTarget.of(scope, workspaceId, agentResourceId, principalId);
    }

    /**
     * Immutable snapshot of this row.
     *
     * @return the view
     */
    public BudgetView view() {
        return new BudgetView(id, target(), period,
                new BudgetLimits(limitTokens, limitCostMicros, currency, softLimitPct, hardLimit), enabled,
                createdAt, updatedAt, getRowVersion());
    }

    public UUID getId() {
        return id;
    }

    public long getRowVersion() {
        return rowVersion == null ? 0L : rowVersion;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Budget other && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
