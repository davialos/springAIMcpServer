package com.springaimcpservercommon.security.principal;

import org.springframework.security.core.Authentication;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Ordered chain of {@link IdentityExtractor}s; the first non-null result wins. The default chain is
 * OAuth 2.0/OIDC (only when {@code spring-security-oauth2-core} is present) → SAML 2.0 (reflective) → LDAP/UserDetails/
 * generic fallback.
 */
public final class IdentityExtraction {

    private final List<IdentityExtractor> extractors;

    /**
     * Creates a chain; a trailing {@link UserDetailsIdentityExtractor} is appended so that the chain never fails.
     *
     * @param extractors extractors in priority order
     */
    public IdentityExtraction(List<? extends IdentityExtractor> extractors) {
        List<IdentityExtractor> list = new ArrayList<>(extractors);
        list.add(new UserDetailsIdentityExtractor());
        this.extractors = List.copyOf(list);
    }

    /**
     * The default chain for the current class path.
     *
     * @return default chain
     */
    public static IdentityExtraction defaults() {
        return withCustom(List.of());
    }

    /**
     * Host extractors first, then the defaults.
     *
     * @param custom host extractors, tried first
     * @return the chain
     */
    public static IdentityExtraction withCustom(List<? extends IdentityExtractor> custom) {
        List<IdentityExtractor> list = new ArrayList<>(custom);
        ClassLoader classLoader = IdentityExtraction.class.getClassLoader();
        if (ClassUtils.isPresent(OAuth2IdentityExtractor.REQUIRED_CLASS, classLoader)) {
            list.add(new OAuth2IdentityExtractor());
        }
        list.add(new Saml2IdentityExtractor());
        return new IdentityExtraction(list);
    }

    /**
     * Extracts identity facts.
     *
     * @param authentication authenticated, non-anonymous authentication
     * @param settings       claim settings
     * @return the facts
     */
    public ExtractedIdentity extract(Authentication authentication, IdentityClaimSettings settings) {
        for (IdentityExtractor extractor : extractors) {
            ExtractedIdentity identity = extractor.extract(authentication, settings);
            if (identity != null) {
                return identity;
            }
        }
        throw new IllegalStateException("fallback extractor returned null");
    }
}
