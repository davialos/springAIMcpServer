package com.springaimcpservercommon.security.web;

import com.springaimcpservercommon.security.apikey.ApiKeyAuthenticationFilter;
import com.springaimcpservercommon.security.internal.ProblemWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * 401 entry point of our API and MCP chains: an RFC 9457 problem plus RFC 6750 / RFC 9728 challenges.
 * <ul>
 *   <li>{@code Bearer} challenge when bearer tokens are accepted — with {@code error="invalid_token"} if a bearer token
 *       was presented, and {@code resource_metadata="…"} (and the default scopes) on the MCP chain (MCP 2025-11-25);</li>
 *   <li>{@code ApiKey realm="dynamic-ai"} challenge when API keys are accepted.</li>
 * </ul>
 * The response never states why a token was rejected.
 */
public final class DaiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final boolean bearer;
    private final boolean apiKeys;
    private final @Nullable String resourceMetadataUrl;
    private final List<String> challengeScopes;

    /**
     * Creates the entry point.
     *
     * @param bearer              whether bearer tokens are accepted on this chain
     * @param apiKeys             whether API keys are accepted on this chain
     * @param resourceMetadataUrl RFC 9728 metadata URL to advertise (MCP chain), or {@code null}
     * @param challengeScopes     scopes to advertise in the 401 challenge (MCP: {@code dai.mcp.read}), may be empty
     */
    public DaiAuthenticationEntryPoint(boolean bearer, boolean apiKeys, @Nullable String resourceMetadataUrl,
                                       List<String> challengeScopes) {
        this.bearer = bearer;
        this.apiKeys = apiKeys;
        this.resourceMetadataUrl = resourceMetadataUrl;
        this.challengeScopes = List.copyOf(challengeScopes);
        if (resourceMetadataUrl != null) {
            WwwAuthenticateHeaders.bearer().resourceMetadata(resourceMetadataUrl); // validate once, fail fast
        }
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        if (bearer) {
            WwwAuthenticateHeaders challenge = WwwAuthenticateHeaders.bearer();
            if (presentedBearer(request)) {
                challenge.error(WwwAuthenticateHeaders.BearerError.INVALID_TOKEN);
            }
            if (resourceMetadataUrl != null) {
                challenge.resourceMetadata(resourceMetadataUrl);
            }
            if (!challengeScopes.isEmpty()) {
                challenge.scopes(challengeScopes);
            }
            response.addHeader("WWW-Authenticate", challenge.build());
        }
        if (apiKeys) {
            response.addHeader("WWW-Authenticate",
                    ApiKeyAuthenticationFilter.SCHEME + " realm=\"" + ApiKeyAuthenticationFilter.REALM + "\"");
        }
        ProblemWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthenticated", "Authentication required");
    }

    private static boolean presentedBearer(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        return authorization != null && authorization.trim().toLowerCase(Locale.ROOT).startsWith("bearer ");
    }
}
