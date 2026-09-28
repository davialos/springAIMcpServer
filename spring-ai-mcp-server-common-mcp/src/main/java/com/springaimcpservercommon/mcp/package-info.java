/**
 * MCP server exposure for the springAIMcpServerCommon library (LLD-07 §5, ADR-0016).
 *
 * <p>Exposes published tool bindings (those with {@code mcpExposed=true}) over the
 * Model Context Protocol (spec 2025-11-25) via Spring AI 2.0's {@code spring-ai-mcp} integration.
 *
 * <p>Default transport: <strong>STATELESS</strong> Streamable HTTP (ADR-0021) at
 * {@code /dynamic-ai/mcp}. Stateful mode is opt-in via
 * {@code dynamic.ai.agent.mcp.server.mode=stateful}.
 *
 * <p>Security: OAuth 2.1 protected resource (RFC 9728); approved-client gate (§5.3);
 * per-call scope + grants check via {@link com.springaimcpservercommon.security.mcp.McpScopeEvaluator}.
 */
@NullMarked
package com.springaimcpservercommon.mcp;

import org.jspecify.annotations.NullMarked;
