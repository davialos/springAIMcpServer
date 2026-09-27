package com.springaimcpservercommon.security.principal;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * An authentication of one of our own service accounts whose store identity is already known (API keys, SEC-01 §9).
 * {@link DefaultAuthorityMapper} maps it without claim extraction: type {@code SERVICE_ACCOUNT}, issuer
 * {@value #ISSUER}, scopes = the key's permissions.
 */
public interface ServiceAccountAuthentication extends Authentication {

    /** Issuer recorded for framework service accounts ({@code dai_principal.issuer}). */
    String ISSUER = "dai";

    /**
     * Returns the resolved service account.
     *
     * @return the identity
     */
    Identity serviceAccount();

    /**
     * Resolved service-account identity.
     *
     * @param principalId      {@code dai_principal.id} of the service account
     * @param serviceAccountId {@code dai_service_account.id}
     * @param workspaceId      owning workspace
     * @param name             service account name (slug)
     * @param permissions      permissions the credential is limited to (API key scopes)
     * @param credentialId     id of the credential (API key id), used for audit and cache keys
     * @param expiresAt        credential expiry
     */
    record Identity(UUID principalId, UUID serviceAccountId, UUID workspaceId, String name, Set<String> permissions,
                    UUID credentialId, @Nullable Instant expiresAt) {
        /**
         * Validates and copies.
         */
        public Identity {
            Objects.requireNonNull(principalId, "principalId");
            Objects.requireNonNull(serviceAccountId, "serviceAccountId");
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(credentialId, "credentialId");
            permissions = Set.copyOf(permissions);
        }
    }
}
