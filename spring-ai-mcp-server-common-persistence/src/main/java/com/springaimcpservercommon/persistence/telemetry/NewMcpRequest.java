package com.springaimcpservercommon.persistence.telemetry;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * A completed MCP JSON-RPC request to record (stateful or stateless).
 *
 * @param id            request id (UUIDv7; tool invocations of the request reference it)
 * @param receivedAt    arrival time
 * @param completedAt   completion time
 * @param mcpSessionId  session, if stateful
 * @param mcpClientId   registered MCP client, if known
 * @param principalId   caller
 * @param workspaceId   workspace, if resolved
 * @param jsonrpcMethod JSON-RPC method, e.g. {@code tools/call}
 * @param jsonrpcId     JSON-RPC id as text, if any
 * @param toolName      tool name for {@code tools/call}
 * @param status        status
 * @param errorCode     error code, if any
 * @param traceId       trace id, if any
 */
public record NewMcpRequest(
        UUID id,
        Instant receivedAt,
        Instant completedAt,
        @Nullable UUID mcpSessionId,
        @Nullable UUID mcpClientId,
        UUID principalId,
        @Nullable UUID workspaceId,
        String jsonrpcMethod,
        @Nullable String jsonrpcId,
        @Nullable String toolName,
        McpRequestStatus status,
        @Nullable String errorCode,
        @Nullable String traceId) {
}
