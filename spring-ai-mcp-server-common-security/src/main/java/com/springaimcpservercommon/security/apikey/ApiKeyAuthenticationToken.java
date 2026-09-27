package com.springaimcpservercommon.security.apikey;

import com.springaimcpservercommon.security.port.ApiKeyLookup.ApiKeyRecord;
import com.springaimcpservercommon.security.principal.ServiceAccountAuthentication;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.io.Serial;
import java.util.List;
import java.util.Objects;

/**
 * Authenticated API key of a service account (SEC-01 §2 "Our API keys"). Holds no credential: the key text is
 * discarded right after verification. Authorities are {@code DAI_SCOPE_<permission>} for the key's scopes, so host
 * rules can recognise them, but the framework authorizes through {@link #serviceAccount()} and its grants.
 */
public final class ApiKeyAuthenticationToken extends AbstractAuthenticationToken implements ServiceAccountAuthentication {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Authority prefix of key scopes. */
    public static final String AUTHORITY_PREFIX = "DAI_SCOPE_";

    private final Identity identity;
    private final String keyPrefix;

    private ApiKeyAuthenticationToken(Identity identity, String keyPrefix, List<GrantedAuthority> authorities) {
        super(authorities);
        this.identity = identity;
        this.keyPrefix = keyPrefix;
        setAuthenticated(true);
    }

    /**
     * Creates an authenticated token from a verified key.
     *
     * @param key verified key record
     * @return the token
     */
    public static ApiKeyAuthenticationToken authenticated(ApiKeyRecord key) {
        Objects.requireNonNull(key, "key");
        Identity identity = new Identity(key.serviceAccountPrincipalId(), key.serviceAccountId(), key.workspaceId(),
                key.serviceAccountName(), key.permissions(), key.id(), key.expiresAt());
        List<GrantedAuthority> authorities = key.permissions().stream().sorted()
                .<GrantedAuthority>map(p -> new SimpleGrantedAuthority(AUTHORITY_PREFIX + p))
                .toList();
        return new ApiKeyAuthenticationToken(identity, key.keyPrefix(), authorities);
    }

    @Override
    public Identity serviceAccount() {
        return identity;
    }

    /**
     * The public key prefix (safe to log).
     *
     * @return {@code dai_<env>_<keyId>}
     */
    public String keyPrefix() {
        return keyPrefix;
    }

    @Override
    public @Nullable Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return identity;
    }

    @Override
    public String getName() {
        return "sa:" + identity.name();
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof ApiKeyAuthenticationToken that && super.equals(that)
                && identity.equals(that.identity) && keyPrefix.equals(that.keyPrefix);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), identity, keyPrefix);
    }
}
