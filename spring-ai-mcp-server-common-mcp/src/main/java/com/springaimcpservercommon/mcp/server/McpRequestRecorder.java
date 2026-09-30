package com.springaimcpservercommon.mcp.server;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * SPI: receives one record per MCP request the endpoint handled (F-72, LLD-15 {@code dai_mcp_request}).
 * Implementations must return quickly and never throw; records hold the JSON-RPC method, tool name and outcome,
 * never arguments or results.
 */
@FunctionalInterface
public interface McpRequestRecorder {

    /** Discards every record. */
    McpRequestRecorder NOOP = call -> { };

    /**
     * Records a handled request.
     *
     * @param call what happened
     */
    void record(McpCall call);

    /** Outcome of a request. */
    enum Status {
        /** Handled successfully (a tool that reported its own error still counts as handled). */
        OK,
        /** Failed: malformed request, unknown method or tool, internal error. */
        ERROR,
        /** The caller was not permitted. */
        DENIED
    }

    /**
     * One handled MCP request.
     *
     * @param id          request id (UUIDv7); tool invocations of this request reference it
     * @param receivedAt  when the request arrived
     * @param completedAt when the response was ready
     * @param principalId caller
     * @param workspaceId workspace the request addressed
     * @param mcpClientId approved MCP client, if known
     * @param method      JSON-RPC method, or {@code unknown}
     * @param jsonrpcId   JSON-RPC id as text, if any (at most 128 characters)
     * @param toolName    tool called, for {@code tools/call}
     * @param status      outcome
     * @param errorCode   stable error code, if any
     * @param traceId     trace id, if any
     */
    record McpCall(UUID id, Instant receivedAt, Instant completedAt, UUID principalId, @Nullable UUID workspaceId,
                   @Nullable UUID mcpClientId, String method, @Nullable String jsonrpcId, @Nullable String toolName,
                   Status status, @Nullable String errorCode, @Nullable String traceId) {

        /** Validates required components. */
        public McpCall {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(receivedAt, "receivedAt");
            Objects.requireNonNull(completedAt, "completedAt");
            Objects.requireNonNull(principalId, "principalId");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(status, "status");
        }
    }
}
