package com.springaimcpservercommon.security.mcp;

import com.springaimcpservercommon.security.authz.AuthorizationOutcome;

import java.util.List;
import java.util.Objects;

/**
 * Result of {@link McpScopeEvaluator#evaluate}.
 */
public sealed interface McpScopeDecision
        permits McpScopeDecision.Allowed, McpScopeDecision.InsufficientScope, McpScopeDecision.Denied {

    /**
     * The call may proceed.
     *
     * @param permits engine permits of every required permission
     */
    record Allowed(List<AuthorizationOutcome.Permit> permits) implements McpScopeDecision {
        /**
         * Copies the list.
         */
        public Allowed {
            permits = List.copyOf(permits);
        }

        /**
         * Whether any permit requires masking.
         *
         * @return {@code true} if data above clearance must be masked
         */
        public boolean maskingRequired() {
            return permits.stream().anyMatch(AuthorizationOutcome.Permit::maskingRequired);
        }
    }

    /**
     * The OAuth token lacks a scope: answer HTTP 403 with {@link #wwwAuthenticate()} so the client can step up
     * (MCP 2025-11-25 "Scope Challenge Handling").
     *
     * @param requiredScopes  scopes to request: the token's relevant MCP scopes plus the missing one
     * @param wwwAuthenticate ready {@code WWW-Authenticate} value ({@code Bearer error="insufficient_scope", …})
     */
    record InsufficientScope(List<String> requiredScopes, String wwwAuthenticate) implements McpScopeDecision {
        /**
         * Copies and validates.
         */
        public InsufficientScope {
            requiredScopes = List.copyOf(requiredScopes);
            Objects.requireNonNull(wwwAuthenticate, "wwwAuthenticate");
        }
    }

    /**
     * Denied by grants, kill switch, classification or API key scope (no step-up possible): answer like an ordinary
     * denied tool call.
     *
     * @param outcome the denial
     */
    record Denied(AuthorizationOutcome.Deny outcome) implements McpScopeDecision {
        /**
         * Validates.
         */
        public Denied {
            Objects.requireNonNull(outcome, "outcome");
        }
    }
}
