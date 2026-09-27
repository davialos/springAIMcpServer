package com.springaimcpservercommon.security.web;

import com.springaimcpservercommon.security.internal.ProblemWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * 403 handler of our chains: a fixed RFC 9457 problem without details (the reason is audited, not disclosed).
 * Step-up challenges ({@code insufficient_scope}) are produced where the missing scope is known
 * (MCP tool calls, {@code McpScopeEvaluator}).
 */
public final class DaiAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        ProblemWriter.write(response, HttpServletResponse.SC_FORBIDDEN, "forbidden", "Access denied");
    }
}
