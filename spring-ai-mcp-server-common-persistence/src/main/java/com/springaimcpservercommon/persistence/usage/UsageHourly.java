package com.springaimcpservercommon.persistence.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Hourly usage aggregate ({@code dai_usage_hourly}). Read-only mapping for queries: rows are written only by
 * {@link UsageLedger} with an atomic native upsert. Derived data, rebuildable from {@code dai_model_call}.
 */
@Entity
@Immutable
@Table(name = "dai_usage_hourly")
public class UsageHourly {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "bucket_start", nullable = false)
    private Instant bucketStart;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "agent_resource_id")
    private @Nullable UUID agentResourceId;

    @Column(name = "principal_id")
    private @Nullable UUID principalId;

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "model", nullable = false)
    private String model;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", length = 3)
    private @Nullable String currency;

    @Column(name = "calls", nullable = false)
    private int calls;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    @Column(name = "cached_input_tokens", nullable = false)
    private long cachedInputTokens;

    @Column(name = "cost_micros", nullable = false)
    private long costMicros;

    /** For JPA only. */
    protected UsageHourly() {
    }

    public Long getId() {
        return id;
    }

    public Instant getBucketStart() {
        return bucketStart;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public @Nullable UUID getAgentResourceId() {
        return agentResourceId;
    }

    public @Nullable UUID getPrincipalId() {
        return principalId;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public @Nullable String getCurrency() {
        return currency;
    }

    public int getCalls() {
        return calls;
    }

    public long getInputTokens() {
        return inputTokens;
    }

    public long getOutputTokens() {
        return outputTokens;
    }

    public long getCachedInputTokens() {
        return cachedInputTokens;
    }

    public long getCostMicros() {
        return costMicros;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof UsageHourly other && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
