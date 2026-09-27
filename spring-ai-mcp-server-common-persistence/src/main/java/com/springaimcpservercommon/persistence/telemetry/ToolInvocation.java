package com.springaimcpservercommon.persistence.telemetry;

import com.springaimcpservercommon.core.catalog.CatalogElementRef;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * One tool invocation executed or proposed for a caller through AI assistance or MCP ({@code dai_tool_invocation},
 * partitioned monthly by {@code started_at}). Insert-only.
 */
@Entity
@Immutable
@Table(name = "dai_tool_invocation")
public class ToolInvocation {

    private static final Set<CatalogElementRef.Kind> TOOL_KINDS = EnumSet.of(CatalogElementRef.Kind.OP,
            CatalogElementRef.Kind.ENTITY, CatalogElementRef.Kind.QUERY, CatalogElementRef.Kind.AGENT,
            CatalogElementRef.Kind.MCP);

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "ended_at", nullable = false, updatable = false)
    private Instant endedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false)
    private Channel channel;

    @Column(name = "turn_id", updatable = false)
    private @Nullable UUID turnId;

    @Column(name = "model_call_id", updatable = false)
    private @Nullable UUID modelCallId;

    @Column(name = "provider_tool_call_id", updatable = false)
    private @Nullable String providerToolCallId;

    @Column(name = "mcp_request_id", updatable = false)
    private @Nullable UUID mcpRequestId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "principal_id", nullable = false, updatable = false)
    private UUID principalId;

    @Column(name = "tool_name", nullable = false, updatable = false)
    private String toolName;

    @Column(name = "element_ref", nullable = false, updatable = false)
    private String elementRef;

    @Column(name = "binding_revision_id", updatable = false)
    private @Nullable UUID bindingRevisionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_mode", nullable = false, updatable = false)
    private ToolAccessMode accessMode;

    @Column(name = "args_hash", nullable = false, updatable = false)
    private String argsHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "args_redacted", updatable = false)
    private @Nullable String argsRedacted;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, updatable = false)
    private ToolInvocationStatus status;

    @Column(name = "row_count", updatable = false)
    private @Nullable Integer rowCount;

    @Column(name = "result_hash", updatable = false)
    private @Nullable String resultHash;

    @Column(name = "truncated", nullable = false, updatable = false)
    private boolean truncated;

    @Column(name = "error_code", updatable = false)
    private @Nullable String errorCode;

    @Column(name = "write_violation", nullable = false, updatable = false)
    private boolean writeViolation;

    @Column(name = "proposal_id", updatable = false)
    private @Nullable UUID proposalId;

    /** For JPA only. */
    protected ToolInvocation() {
    }

    /**
     * Creates an invocation row after validating the {@code ck_tool_invocation_*} rules.
     *
     * @param inv the completed invocation
     * @return a new, unsaved entity
     */
    public static ToolInvocation of(NewToolInvocation inv) {
        ToolInvocation t = new ToolInvocation();
        t.id = Checks.required(inv.id(), "id");
        t.startedAt = UtcTimes.micros(Checks.required(inv.startedAt(), "startedAt"));
        t.endedAt = UtcTimes.micros(Checks.required(inv.endedAt(), "endedAt"));
        Checks.notBefore(t.startedAt, t.endedAt, "tool invocation");
        t.channel = Checks.required(inv.channel(), "channel");
        t.turnId = inv.turnId();
        t.mcpRequestId = inv.mcpRequestId();
        switch (t.channel) {
            case CHAT, PLAYGROUND -> {
                if (t.turnId == null) {
                    throw new IllegalArgumentException("turnId is required for " + t.channel + " tool invocations");
                }
            }
            case MCP -> {
                if (t.mcpRequestId == null) {
                    throw new IllegalArgumentException("mcpRequestId is required for MCP tool invocations");
                }
            }
            case ENDPOINT -> {
                // no origin reference required
            }
        }
        t.modelCallId = inv.modelCallId();
        t.providerToolCallId = Checks.optionalText(inv.providerToolCallId(), "providerToolCallId", 256);
        t.workspaceId = Checks.required(inv.workspaceId(), "workspaceId");
        t.principalId = Checks.required(inv.principalId(), "principalId");
        t.toolName = Checks.matches(inv.toolName(), McpRequest.TOOL_NAME, "toolName");
        CatalogElementRef ref = Checks.required(inv.elementRef(), "elementRef");
        if (!TOOL_KINDS.contains(ref.kind())) {
            throw new IllegalArgumentException("elementRef must be of kind op, entity, query, agent or mcp: " + ref);
        }
        t.elementRef = ref.toString();
        t.bindingRevisionId = inv.bindingRevisionId();
        t.accessMode = Checks.required(inv.accessMode(), "accessMode");
        t.argsHash = Checks.sha256(inv.argsHash(), "argsHash");
        t.argsRedacted = Checks.optionalJson(inv.argsRedactedJson(), "argsRedactedJson");
        t.status = Checks.required(inv.status(), "status");
        t.proposalId = inv.proposalId();
        if ((t.status == ToolInvocationStatus.PROPOSED) != (t.proposalId != null)) {
            throw new IllegalArgumentException("proposalId must be set exactly when the status is PROPOSED");
        }
        if (t.status == ToolInvocationStatus.PROPOSED && t.accessMode != ToolAccessMode.PROPOSE) {
            throw new IllegalArgumentException("status PROPOSED requires access mode PROPOSE");
        }
        Integer rows = inv.rowCount();
        if (rows != null) {
            Checks.nonNegative(rows, "rowCount");
        }
        t.rowCount = rows;
        String resultHash = inv.resultHash();
        t.resultHash = resultHash == null ? null : Checks.sha256(resultHash, "resultHash");
        t.truncated = inv.truncated();
        t.errorCode = Checks.optionalText(inv.errorCode(), "errorCode", 128);
        t.writeViolation = inv.writeViolation();
        return t;
    }

    /** @return invocation id */
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

    /** @return entry channel */
    public Channel getChannel() {
        return channel;
    }

    /** @return agent turn, if any */
    public @Nullable UUID getTurnId() {
        return turnId;
    }

    /** @return model call, if any */
    public @Nullable UUID getModelCallId() {
        return modelCallId;
    }

    /** @return provider tool call id, if any */
    public @Nullable String getProviderToolCallId() {
        return providerToolCallId;
    }

    /** @return MCP request, if any */
    public @Nullable UUID getMcpRequestId() {
        return mcpRequestId;
    }

    /** @return workspace id */
    public UUID getWorkspaceId() {
        return workspaceId;
    }

    /** @return caller */
    public UUID getPrincipalId() {
        return principalId;
    }

    /** @return tool name */
    public String getToolName() {
        return toolName;
    }

    /** @return invoked catalog element */
    public CatalogElementRef getElementRef() {
        return CatalogElementRef.parse(elementRef);
    }

    /** @return binding revision, if any */
    public @Nullable UUID getBindingRevisionId() {
        return bindingRevisionId;
    }

    /** @return access mode */
    public ToolAccessMode getAccessMode() {
        return accessMode;
    }

    /** @return arguments hash */
    public String getArgsHash() {
        return argsHash;
    }

    /** @return redacted arguments JSON, if kept */
    public @Nullable String getArgsRedactedJson() {
        return argsRedacted;
    }

    /** @return status */
    public ToolInvocationStatus getStatus() {
        return status;
    }

    /** @return rows returned, if applicable */
    public @Nullable Integer getRowCount() {
        return rowCount;
    }

    /** @return result hash, if any */
    public @Nullable String getResultHash() {
        return resultHash;
    }

    /** @return whether the result was truncated */
    public boolean isTruncated() {
        return truncated;
    }

    /** @return error code, if any */
    public @Nullable String getErrorCode() {
        return errorCode;
    }

    /** @return whether the write guard vetoed a write attempt */
    public boolean isWriteViolation() {
        return writeViolation;
    }

    /** @return created proposal, if any */
    public @Nullable UUID getProposalId() {
        return proposalId;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof ToolInvocation other && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "ToolInvocation[" + id + ", " + toolName + "]";
    }
}
