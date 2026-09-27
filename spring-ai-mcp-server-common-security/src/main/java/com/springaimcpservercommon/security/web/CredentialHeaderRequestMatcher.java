package com.springaimcpservercommon.security.web;

import com.springaimcpservercommon.security.apikey.ApiKeyAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.Locale;

/**
 * Matches requests that carry explicit, non-ambient credentials ({@code Authorization: Bearer|ApiKey …} or
 * {@code X-DAI-Api-Key}). Browsers never attach these cross-site on their own, so such requests are exempt from CSRF
 * checks when a chain also accepts session cookies. HTTP Basic is deliberately <em>not</em> matched: browsers replay
 * cached Basic credentials automatically.
 */
public final class CredentialHeaderRequestMatcher implements RequestMatcher {

    @Override
    public boolean matches(HttpServletRequest request) {
        if (request.getHeader(ApiKeyAuthenticationFilter.HEADER) != null) {
            return true;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null) {
            return false;
        }
        String value = authorization.trim().toLowerCase(Locale.ROOT);
        return value.startsWith("bearer ") || value.startsWith(ApiKeyAuthenticationFilter.SCHEME.toLowerCase(Locale.ROOT) + " ");
    }
}
