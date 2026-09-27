package com.springaimcpservercommon.security.permission;

import java.util.Arrays;
import java.util.Optional;

/**
 * OAuth scopes of the MCP endpoint (LLD-07 §5.4). Values match {@code dai_mcp_client_consent_scope.scope}.
 *
 * <p>Service accounts authenticated by API key cannot carry dotted OAuth scopes, so each scope has an equivalent
 * API key permission ({@link #apiKeyPermission()}).
 */
public enum McpScope {

    /** List tools and call {@code readOnly=true} tools. */
    READ("dai.mcp.read", Permission.MCP_READ),
    /** Call write tools, which only create change proposals (LLD-11). */
    PROPOSE("dai.mcp.propose", Permission.MCP_PROPOSE),
    /** Call {@code ask_<agent>} tools. */
    AGENTS("dai.mcp.agents", Permission.MCP_AGENTS);

    private final String value;
    private final Permission apiKeyPermission;

    McpScope(String value, Permission apiKeyPermission) {
        this.value = value;
        this.apiKeyPermission = apiKeyPermission;
    }

    /**
     * Returns the OAuth scope string.
     *
     * @return e.g. {@code dai.mcp.read}
     */
    public String value() {
        return value;
    }

    /**
     * Returns the API key permission that stands for this scope.
     *
     * @return e.g. {@link Permission#MCP_READ}
     */
    public Permission apiKeyPermission() {
        return apiKeyPermission;
    }

    /**
     * Resolves an OAuth scope string.
     *
     * @param scope scope as found in a token (case-sensitive, as OAuth scopes are)
     * @return the scope, or empty if it is not one of ours
     */
    public static Optional<McpScope> fromValue(String scope) {
        return Arrays.stream(values()).filter(s -> s.value.equals(scope)).findFirst();
    }

    @Override
    public String toString() {
        return value;
    }
}
