package com.springaimcpservercommon.persistence.identity;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable view of an API key with everything needed to authenticate a request. Contains the stored hash (never
 * the secret); do not log it.
 *
 * @param id                 key id
 * @param serviceAccountId   owning service account
 * @param principalId        principal of the service account (the authenticated subject)
 * @param workspaceId        workspace of the service account
 * @param keyPrefix          public lookup prefix
 * @param keyHash            hash of the secret part
 * @param hashAlgorithm      algorithm of {@code keyHash}
 * @param expiresAt          expiry
 * @param lastUsedAt         last use (throttled, see {@link ApiKeyStore#touchLastUsed})
 * @param revokedAt          revocation time, if revoked
 * @param scopes             permissions of the key
 * @param allowedNetworks    CIDR allow-list in PostgreSQL text form; empty = any network
 */
public record ApiKeyView(
        UUID id,
        UUID serviceAccountId,
        UUID principalId,
        UUID workspaceId,
        String keyPrefix,
        String keyHash,
        ApiKeyHashAlgorithm hashAlgorithm,
        Instant expiresAt,
        @Nullable Instant lastUsedAt,
        @Nullable Instant revokedAt,
        Set<String> scopes,
        List<String> allowedNetworks) {

    /** Validates and copies collections. */
    public ApiKeyView {
        Objects.requireNonNull(id, "id");
        scopes = Set.copyOf(scopes);
        allowedNetworks = List.copyOf(allowedNetworks);
    }

    @Override
    public String toString() {
        return "ApiKeyView[id=" + id + ", keyPrefix=" + keyPrefix + ", serviceAccountId=" + serviceAccountId + "]";
    }
}
