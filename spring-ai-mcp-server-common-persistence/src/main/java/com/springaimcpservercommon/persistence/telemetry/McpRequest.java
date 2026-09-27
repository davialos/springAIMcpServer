package com.springaimcpservercommon.persistence.telemetry;

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
import java.util.regex.Pattern;

/**
 * One MCP JSON-RPC request ({@code dai_mcp_request}, partitioned monthly by {@code received_at}). Insert-only.
 */
@Entity
@Immutable
@Table(name = "dai_mcp_request")
public class McpRequest {

    static final Pattern METHOD = Pattern.compile("^[a-z][A-Za-z/_]{1,63}$");
    static final Pattern TOOL_NAME = Pattern.compile("^[a-z][a-z0-9_]{2,63}$");

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "completed_at", nullable = false, updatable = false)
    private Instant completedAt;

    @Column(name = "mcp_session_id", updatable = false)
    private @Nullable UUID mcpSessionId;

    @Column(name = "mcp_client_id", updatable = false)
    private @Nullable UUID mcpClientId;

    @Column(name = "principal_id", nullable = false, updatable = false)
    private UUID principalId;

    @Column(name = "workspace_id", updatable = false)
    private @Nullable UUID workspaceId;

    @Column(name = "jsonrpc_method", nullable = false, updatable = false)
    private String jsonrpcMethod;

    @Column(name = "jsonrpc_id", updatable = false)
    private @Nullable String jsonrpcId;

    @Column(name = "tool_name", updatable = false)
    private @Nullable String toolName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, updatable = false)
    private McpRequestStatus status;

    @Column(name = "error_code", updatable = false)
    private @Nullable String errorCode;

    @Column(name = "trace_id", updatable = false)
    private @Nullable String traceId;

    /** For JPA only. */
    protected McpRequest() {
    }

    /**
     * Creates a request row after validating the {@code ck_mcp_request_*} rules.
     *
     * @param request the completed request
     * @return a new, unsaved entity
     */
    public static McpRequest of(NewMcpRequest request) {
        McpRequest r = new McpRequest();
        r.id = Checks.required(request.id(), "id");
        r.receivedAt = UtcTimes.micros(Checks.required(request.receivedAt(), "receivedAt"));
        r.completedAt = UtcTimes.micros(Checks.required(request.completedAt(), "completedAt"));
        Checks.notBefore(r.receivedAt, r.completedAt, "MCP request");
        r.mcpSessionId = request.mcpSessionId();
        r.mcpClientId = request.mcpClientId();
        r.principalId = Checks.required(request.principalId(), "principalId");
        r.workspaceId = request.workspaceId();
        r.jsonrpcMethod = Checks.matches(request.jsonrpcMethod(), METHOD, "jsonrpcMethod");
        r.jsonrpcId = Checks.optionalText(request.jsonrpcId(), "jsonrpcId", 256);
        r.toolName = Checks.optionalMatches(request.toolName(), TOOL_NAME, "toolName");
        r.status = Checks.required(request.status(), "status");
        r.errorCode = Checks.optionalText(request.errorCode(), "errorCode", 128);
        r.traceId = Checks.optionalText(request.traceId(), "traceId", 128);
        return r;
    }

    /** @return request id */
    public UUID getId() {
        return id;
    }

    /** @return arrival time */
    public Instant getReceivedAt() {
        return receivedAt;
    }

    /** @return completion time */
    public Instant getCompletedAt() {
        return completedAt;
    }

    /** @return session id, if stateful */
    public @Nullable UUID getMcpSessionId() {
        return mcpSessionId;
    }

    /** @return MCP client id, if known */
    public @Nullable UUID getMcpClientId() {
        return mcpClientId;
    }

    /** @return caller */
    public UUID getPrincipalId() {
        return principalId;
    }

    /** @return workspace id, if resolved */
    public @Nullable UUID getWorkspaceId() {
        return workspaceId;
    }

    /** @return JSON-RPC method */
    public String getJsonrpcMethod() {
        return jsonrpcMethod;
    }

    /** @return JSON-RPC id, if any */
    public @Nullable String getJsonrpcId() {
        return jsonrpcId;
    }

    /** @return tool name, if a tool call */
    public @Nullable String getToolName() {
        return toolName;
    }

    /** @return status */
    public McpRequestStatus getStatus() {
        return status;
    }

    /** @return error code, if any */
    public @Nullable String getErrorCode() {
        return errorCode;
    }

    /** @return trace id, if any */
    public @Nullable String getTraceId() {
        return traceId;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof McpRequest other && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "McpRequest[" + id + "]";
    }
}
