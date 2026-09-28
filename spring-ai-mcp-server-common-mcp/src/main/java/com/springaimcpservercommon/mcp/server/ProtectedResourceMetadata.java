package com.springaimcpservercommon.mcp.server;

import com.springaimcpservercommon.core.json.CanonicalJson;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * OAuth 2.1 Protected Resource Metadata (RFC 9728) published at
 * {@code /.well-known/oauth-protected-resource{base}/mcp} (LLD-07 §5.3).
 *
 * <p>Tells MCP clients which authorization server to use when obtaining tokens, the resource URI
 * for audience binding (RFC 8707), and the required scopes per action type.
 *
 * @param resourceUri          the absolute URI of this MCP resource ({@code https://host/dynamic-ai/mcp})
 * @param authorizationServers list of authorization server issuer URIs that can issue tokens for this resource
 * @param scopesSupported      supported scope strings ({@code dai.mcp.read}, {@code dai.mcp.propose}, {@code dai.mcp.agents})
 * @param bearerMethodsSupported bearer token methods (always {@code ["header"]})
 * @param resourceDocumentation optional documentation URI
 */
public record ProtectedResourceMetadata(
        String resourceUri,
        List<String> authorizationServers,
        List<String> scopesSupported,
        List<String> bearerMethodsSupported,
        @Nullable String resourceDocumentation) {

    /** Scopes required per action type (LLD-07 §5.4). */
    public static final List<String> DEFAULT_SCOPES =
            List.of("dai.mcp.read", "dai.mcp.propose", "dai.mcp.agents");

    /** Validates and copies. */
    public ProtectedResourceMetadata {
        Objects.requireNonNull(resourceUri, "resourceUri");
        authorizationServers = List.copyOf(authorizationServers);
        scopesSupported = List.copyOf(scopesSupported);
        bearerMethodsSupported = List.copyOf(bearerMethodsSupported);
    }

    /**
     * Builds the RFC 9728 JSON payload.
     *
     * @return JSON string
     */
    public String toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("resource", resourceUri);
        map.put("authorization_servers", authorizationServers);
        map.put("scopes_supported", scopesSupported);
        map.put("bearer_methods_supported", bearerMethodsSupported);
        if (resourceDocumentation != null) map.put("resource_documentation", resourceDocumentation);
        return CanonicalJson.write(map);
    }

    /**
     * Builds metadata for a single authorization server.
     *
     * @param resourceUri        the MCP resource URI
     * @param authorizationServer the issuer URI of the authorization server
     * @return the metadata instance
     */
    public static ProtectedResourceMetadata forSingleIssuer(String resourceUri, String authorizationServer) {
        return new ProtectedResourceMetadata(
                resourceUri,
                List.of(authorizationServer),
                DEFAULT_SCOPES,
                List.of("header"),
                null);
    }
}
