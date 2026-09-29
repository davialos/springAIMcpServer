package com.springaimcpservercommon.persistence.telemetry;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Optional narrowing of a workspace's MCP request list; a {@code null} component does not filter.
 *
 * @param method      only this JSON-RPC method (for example {@code tools/call})
 * @param toolName    only calls of this tool
 * @param status      only this status
 * @param principalId only requests of this caller
 */
public record McpRequestFilter(@Nullable String method, @Nullable String toolName,
                               @Nullable McpRequestStatus status, @Nullable UUID principalId) {

    /** No filtering. */
    public static final McpRequestFilter NONE = new McpRequestFilter(null, null, null, null);
}
