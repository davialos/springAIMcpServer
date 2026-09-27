package com.springaimcpservercommon.security.web;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Settings of our filter chains ({@code dynamic.ai.agent.security.*}, bound by autoconfigure).
 *
 * @param basePath                   base path of all planes, default {@code /dynamic-ai}
 * @param apiKeysEnabled             whether service-account API keys are accepted on the API and MCP chains
 * @param acceptApiKeyHeader         whether {@code X-DAI-Api-Key} is accepted besides {@code Authorization: ApiKey}
 * @param dataPlaneSessions          whether the data plane also accepts the host's session login (CSRF-protected);
 *                                   the MCP endpoint never does
 * @param adminLoginUrl              where the admin UI sends unauthenticated browsers; {@code null} = derive from the
 *                                   host ({@code /oauth2/authorization/<id>} for a single registration, else
 *                                   {@code /login} when the host has a session login)
 * @param adminContentSecurityPolicy CSP of the admin UI
 * @param mcpResourceMetadataUrl     RFC 9728 metadata URL advertised in MCP 401 challenges, e.g.
 *                                   {@code https://app.example.com/.well-known/oauth-protected-resource/dynamic-ai/mcp}
 * @param mcpChallengeScopes         scopes advertised in MCP 401 challenges (least privilege: {@code dai.mcp.read})
 */
public record DynamicAiSecurityOptions(
        String basePath,
        boolean apiKeysEnabled,
        boolean acceptApiKeyHeader,
        boolean dataPlaneSessions,
        @Nullable String adminLoginUrl,
        String adminContentSecurityPolicy,
        @Nullable String mcpResourceMetadataUrl,
        List<String> mcpChallengeScopes) {

    /** Strict CSP for the embedded admin UI (prebuilt assets served from our own path, LLD-08). */
    public static final String DEFAULT_ADMIN_CSP = "default-src 'self'; script-src 'self'; style-src 'self'; "
            + "img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; "
            + "form-action 'self'; frame-ancestors 'none'";
    /** CSP for JSON APIs: nothing may be loaded or framed. */
    public static final String API_CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'";

    private static final Pattern BASE_PATH = Pattern.compile("^/[a-z0-9][a-z0-9-]*(/[a-z0-9][a-z0-9-]*)*$");

    /**
     * Validates.
     */
    public DynamicAiSecurityOptions {
        Objects.requireNonNull(basePath, "basePath");
        if (!BASE_PATH.matcher(basePath).matches()) {
            throw new IllegalArgumentException("basePath must look like /dynamic-ai (lower case, no trailing slash)");
        }
        if (adminContentSecurityPolicy == null || adminContentSecurityPolicy.isBlank()
                || !adminContentSecurityPolicy.contains("frame-ancestors")) {
            throw new IllegalArgumentException("admin CSP must be set and contain frame-ancestors");
        }
        mcpChallengeScopes = List.copyOf(mcpChallengeScopes);
    }

    /**
     * Defaults: {@code /dynamic-ai}, API keys on via {@code Authorization: ApiKey} only, no data-plane sessions.
     *
     * @return defaults
     */
    public static DynamicAiSecurityOptions defaults() {
        return new DynamicAiSecurityOptions("/dynamic-ai", true, false, false, null, DEFAULT_ADMIN_CSP, null,
                List.of("dai.mcp.read"));
    }

    /**
     * Admin plane pattern.
     *
     * @return {@code <base>/admin/**}
     */
    public String adminPattern() {
        return basePath + "/admin/**";
    }

    /**
     * MCP endpoint patterns.
     *
     * @return {@code <base>/mcp} and {@code <base>/mcp/**}
     */
    public String[] mcpPatterns() {
        return new String[]{basePath + "/mcp", basePath + "/mcp/**"};
    }

    /**
     * Pattern of everything under the base path.
     *
     * @return {@code <base>/**}
     */
    public String allPattern() {
        return basePath + "/**";
    }
}
