package com.springaimcpservercommon.security.mcp;

import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;

import java.util.Collection;
import java.util.Objects;

/**
 * RFC 8707 audience binding for MCP tokens (LLD-07 §5.3): tokens minted for other APIs must be rejected. Autoconfigure
 * combines this validator with the host's issuer validation when it builds the MCP chain's {@code JwtDecoder}.
 *
 * <p>References {@code spring-security-oauth2-jose}; use only when that module is present.
 */
public final class McpAudienceValidators {

    private McpAudienceValidators() {
    }

    /**
     * A validator requiring the canonical MCP resource URI in {@code aud}.
     *
     * @param resourceUri canonical URI of our MCP endpoint, e.g. {@code https://app.example.com/dynamic-ai/mcp}
     * @return the validator
     */
    public static OAuth2TokenValidator<Jwt> requireAudience(String resourceUri) {
        Objects.requireNonNull(resourceUri, "resourceUri");
        String canonical = resourceUri.endsWith("/") ? resourceUri.substring(0, resourceUri.length() - 1) : resourceUri;
        return new JwtClaimValidator<Collection<String>>("aud",
                aud -> aud != null && (aud.contains(canonical) || aud.contains(canonical + "/")));
    }
}
