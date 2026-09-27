package com.springaimcpservercommon.security.apikey;

import com.springaimcpservercommon.security.internal.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * Authenticates service accounts by API key (SEC-01 §9) inside our filter chains only (it is never registered as a
 * global servlet filter — LLD-12 §4).
 *
 * <ul>
 *   <li>Reads {@code Authorization: ApiKey <key>} and, if enabled, {@code X-DAI-Api-Key: <key>}.</li>
 *   <li>Requests without a key pass through untouched (other mechanisms, e.g. bearer tokens, may authenticate).</li>
 *   <li>Ambiguous credentials are rejected with 400: both headers, an API key header next to any
 *       {@code Authorization} header (bearer/basic), or an API key on a request already authenticated by a session.</li>
 *   <li>Failures answer a generic 401 ({@code WWW-Authenticate: ApiKey realm="dynamic-ai"}) whatever the reason,
 *       and count against the client IP in a bounded {@link FailedAttemptLimiter}; blocked clients get 429 with
 *       {@code Retry-After} before any lookup.</li>
 *   <li>Success sets an {@link ApiKeyAuthenticationToken} in a fresh {@link SecurityContext} for this request only
 *       (nothing is saved to a session). The key text is never logged; only its public prefix and the reason code.</li>
 * </ul>
 * The client IP is {@link HttpServletRequest#getRemoteAddr()}: hosts behind proxies must configure forwarded-header
 * handling (e.g. {@code server.forward-headers-strategy}) — this filter never trusts {@code X-Forwarded-For} itself.
 */
public final class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    /** Authorization scheme of API keys. */
    public static final String SCHEME = "ApiKey";
    /** Optional dedicated header. */
    public static final String HEADER = "X-DAI-Api-Key";
    /** Realm used in challenges. */
    public static final String REALM = "dynamic-ai";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

    private final ApiKeyService service;
    private final FailedAttemptLimiter limiter;
    private final boolean acceptDedicatedHeader;
    private SecurityContextHolderStrategy contextHolderStrategy = SecurityContextHolder.getContextHolderStrategy();

    /**
     * Creates the filter.
     *
     * @param service               key verification
     * @param limiter               failed-attempt limiter
     * @param acceptDedicatedHeader whether {@value #HEADER} is accepted in addition to the Authorization scheme
     */
    public ApiKeyAuthenticationFilter(ApiKeyService service, FailedAttemptLimiter limiter, boolean acceptDedicatedHeader) {
        this.service = Objects.requireNonNull(service, "service");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.acceptDedicatedHeader = acceptDedicatedHeader;
    }

    /**
     * Sets the security context holder strategy (defaults to the global one).
     *
     * @param strategy strategy
     */
    public void setSecurityContextHolderStrategy(SecurityContextHolderStrategy strategy) {
        this.contextHolderStrategy = Objects.requireNonNull(strategy, "strategy");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        String schemeKey = apiKeyFromAuthorization(authorization);
        String headerKey = acceptDedicatedHeader ? trimToNull(request.getHeader(HEADER)) : null;
        if (schemeKey == null && headerKey == null) {
            chain.doFilter(request, response);
            return;
        }
        if (headerKey != null && authorization != null) {
            ProblemWriter.write(response, HttpServletResponse.SC_BAD_REQUEST, "ambiguous-credentials",
                    "Send either an API key or an Authorization header, not both");
            return;
        }
        if (alreadyAuthenticated()) {
            ProblemWriter.write(response, HttpServletResponse.SC_BAD_REQUEST, "ambiguous-credentials",
                    "Request is already authenticated");
            return;
        }

        String client = request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
        Duration blocked = limiter.blockedFor(client);
        if (!blocked.isZero()) {
            response.setHeader("Retry-After", Long.toString(Math.max(1, blocked.toSeconds())));
            ProblemWriter.write(response, 429, "too-many-failed-attempts", "Too many failed authentication attempts");
            return;
        }

        String key = schemeKey != null ? schemeKey : headerKey;
        ApiKeyVerification verification = service.verify(key, CidrBlock.literal(request.getRemoteAddr()));
        if (verification instanceof ApiKeyVerification.Invalid invalid) {
            limiter.recordFailure(client);
            ApiKeyFormat.ParsedApiKey parsed = ApiKeyFormat.parse(key);
            log.info("API key rejected: {} (prefix {})", invalid.reason(), parsed == null ? "-" : parsed.prefix());
            response.setHeader("WWW-Authenticate", SCHEME + " realm=\"" + REALM + "\"");
            ProblemWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthenticated", "Authentication required");
            return;
        }
        ApiKeyVerification.Valid valid = (ApiKeyVerification.Valid) verification;
        ApiKeyAuthenticationToken token = ApiKeyAuthenticationToken.authenticated(valid.key());
        SecurityContext context = contextHolderStrategy.createEmptyContext();
        context.setAuthentication(token);
        contextHolderStrategy.setContext(context);
        chain.doFilter(request, response);
    }

    private boolean alreadyAuthenticated() {
        Authentication current = contextHolderStrategy.getContext().getAuthentication();
        return current != null && current.isAuthenticated() && !(current instanceof AnonymousAuthenticationToken);
    }

    private static @Nullable String apiKeyFromAuthorization(@Nullable String authorization) {
        if (authorization == null) {
            return null;
        }
        String value = authorization.trim();
        int space = value.indexOf(' ');
        if (space <= 0 || !value.substring(0, space).toLowerCase(Locale.ROOT).equals(SCHEME.toLowerCase(Locale.ROOT))) {
            return null;
        }
        return trimToNull(value.substring(space + 1));
    }

    private static @Nullable String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
