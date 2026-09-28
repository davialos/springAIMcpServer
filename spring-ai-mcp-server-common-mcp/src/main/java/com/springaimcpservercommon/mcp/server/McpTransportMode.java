package com.springaimcpservercommon.mcp.server;

/**
 * MCP server transport mode (ADR-0021, LLD-07 §5.1).
 *
 * <p>The default is {@link #STATELESS}: any replica can serve any request on a plain
 * round-robin load balancer. {@link #STATEFUL} is opt-in and requires either sticky sessions
 * or a shared session store across replicas.
 */
public enum McpTransportMode {

    /**
     * Streamable HTTP, stateless (default).
     * Maps to {@code spring.ai.mcp.server.protocol=STATELESS}.
     * Each request is independent; no server-to-client notifications ({@code tools/list_changed}).
     */
    STATELESS,

    /**
     * Streamable HTTP, stateful (opt-in).
     * Maps to {@code spring.ai.mcp.server.protocol=STREAMABLE}.
     * Enables live {@code tools/list_changed} push; requires sticky sessions or shared session store.
     */
    STATEFUL
}
