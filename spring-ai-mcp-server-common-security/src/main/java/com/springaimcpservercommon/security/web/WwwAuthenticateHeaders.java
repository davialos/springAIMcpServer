package com.springaimcpservercommon.security.web;

import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds {@code WWW-Authenticate} challenge values for the {@code Bearer} scheme per RFC 6750 §3, RFC 9728 §5.1
 * ({@code resource_metadata}) and the MCP 2025-11-25 authorization spec, e.g.
 * <pre>
 * Bearer resource_metadata="https://app.example.com/.well-known/oauth-protected-resource/dynamic-ai/mcp", scope="dai.mcp.read"
 * Bearer error="insufficient_scope", scope="dai.mcp.read dai.mcp.propose", resource_metadata="…", error_description="…"
 * </pre>
 * Parameter values are validated against the RFC 6750 character sets instead of being escaped, so a header can never
 * be split or smuggle extra parameters.
 */
public final class WwwAuthenticateHeaders {

    /** RFC 6750 error codes. */
    public enum BearerError {
        /** Malformed request (400). */
        INVALID_REQUEST("invalid_request"),
        /** Missing/expired/invalid token (401). */
        INVALID_TOKEN("invalid_token"),
        /** Token lacks the required scope (403). */
        INSUFFICIENT_SCOPE("insufficient_scope");

        private final String code;

        BearerError(String code) {
            this.code = code;
        }

        /**
         * Wire value.
         *
         * @return the code
         */
        public String code() {
            return code;
        }
    }

    private @Nullable String realm;
    private @Nullable BearerError error;
    private final Set<String> scopes = new LinkedHashSet<>();
    private @Nullable String resourceMetadata;
    private @Nullable String errorDescription;

    private WwwAuthenticateHeaders() {
    }

    /**
     * Starts a {@code Bearer} challenge.
     *
     * @return a builder
     */
    public static WwwAuthenticateHeaders bearer() {
        return new WwwAuthenticateHeaders();
    }

    /**
     * Sets the realm.
     *
     * @param value realm
     * @return this builder
     */
    public WwwAuthenticateHeaders realm(String value) {
        this.realm = requireQuotable(value, "realm");
        return this;
    }

    /**
     * Sets the error code.
     *
     * @param value error
     * @return this builder
     */
    public WwwAuthenticateHeaders error(BearerError value) {
        this.error = value;
        return this;
    }

    /**
     * Adds required scopes (space-delimited in the header).
     *
     * @param values scope tokens ({@code NQCHAR} only)
     * @return this builder
     */
    public WwwAuthenticateHeaders scopes(Collection<String> values) {
        for (String scope : values) {
            if (scope.isEmpty() || !scope.chars().allMatch(WwwAuthenticateHeaders::isNqChar)) {
                throw new IllegalArgumentException("invalid scope token");
            }
            scopes.add(scope);
        }
        return this;
    }

    /**
     * Sets the RFC 9728 protected resource metadata URL.
     *
     * @param url absolute https URL (http only for localhost)
     * @return this builder
     */
    public WwwAuthenticateHeaders resourceMetadata(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("resource_metadata must be an absolute URI", e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean local = "localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
        if (!uri.isAbsolute() || uri.getFragment() != null
                || !("https".equals(scheme) || ("http".equals(scheme) && local))) {
            throw new IllegalArgumentException("resource_metadata must be an absolute https URL without fragment");
        }
        this.resourceMetadata = requireQuotable(url, "resource_metadata");
        return this;
    }

    /**
     * Sets a human-readable description (fixed, code-defined text — never exception messages).
     *
     * @param value description
     * @return this builder
     */
    public WwwAuthenticateHeaders errorDescription(String value) {
        this.errorDescription = requireQuotable(value, "error_description");
        return this;
    }

    /**
     * Renders the header value.
     *
     * @return e.g. {@code Bearer error="insufficient_scope", scope="a b"}
     */
    public String build() {
        List<String> params = new ArrayList<>();
        if (realm != null) {
            params.add("realm=\"" + realm + "\"");
        }
        if (error != null) {
            params.add("error=\"" + error.code() + "\"");
        }
        if (!scopes.isEmpty()) {
            params.add("scope=\"" + String.join(" ", scopes) + "\"");
        }
        if (resourceMetadata != null) {
            params.add("resource_metadata=\"" + resourceMetadata + "\"");
        }
        if (errorDescription != null) {
            params.add("error_description=\"" + errorDescription + "\"");
        }
        return params.isEmpty() ? "Bearer" : "Bearer " + String.join(", ", params);
    }

    /** RFC 6750 NQCHAR = %x21 / %x23-5B / %x5D-7E. */
    private static boolean isNqChar(int c) {
        return c == 0x21 || (c >= 0x23 && c <= 0x5B) || (c >= 0x5D && c <= 0x7E);
    }

    /** RFC 6750 error_description charset: %x20-21 / %x23-5B / %x5D-7E (no quote, no backslash, no controls). */
    private static String requireQuotable(String value, String name) {
        if (value.isEmpty() || value.length() > 2048
                || !value.chars().allMatch(c -> c == 0x20 || isNqChar(c))) {
            throw new IllegalArgumentException(name + " contains characters not allowed in a quoted parameter");
        }
        return value;
    }
}
