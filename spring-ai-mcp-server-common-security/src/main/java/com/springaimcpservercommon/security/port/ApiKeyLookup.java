package com.springaimcpservercommon.security.port;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Port to {@code dai_api_key} (+ scopes, allowed networks, owning service account) for API key authentication
 * (SEC-01 §9).
 */
public interface ApiKeyLookup {

    /**
     * Finds a key by its public prefix ({@code dai_<env>_<keyId>}), including revoked or expired keys (the service
     * reports those precisely and audits them); returns empty only when no row exists.
     *
     * @param keyPrefix public prefix
     * @return the key
     */
    Optional<ApiKeyRecord> findByPrefix(String keyPrefix);

    /**
     * Records use of a key ({@code last_used_at}). Called at most once per key per touch interval.
     *
     * @param apiKeyId key id
     * @param at       use time
     */
    void touchLastUsed(UUID apiKeyId, Instant at);

    /**
     * One API key with what authentication needs.
     *
     * @param id                        key id
     * @param keyPrefix                 public prefix
     * @param keyHash                   stored hash ({@code v<pepperVersion>:<base64url HMAC>} for {@code hmac-sha256})
     * @param hashAlgorithm             {@code dai_api_key.hash_algorithm}
     * @param expiresAt                 expiry (mandatory)
     * @param revokedAt                 revocation time, if revoked
     * @param lastUsedAt                last use, if any
     * @param serviceAccountId          owning service account
     * @param serviceAccountPrincipalId {@code dai_principal.id} of the service account
     * @param serviceAccountName        service account name
     * @param serviceAccountActive      whether the service account is {@code ACTIVE}
     * @param workspaceId               owning workspace
     * @param permissions               key scopes ({@code dai_api_key_scope.permission})
     * @param allowedNetworks           CIDR allow-list ({@code dai_api_key_allowed_network}); empty = any network
     */
    record ApiKeyRecord(UUID id, String keyPrefix, String keyHash, String hashAlgorithm, Instant expiresAt,
                        @Nullable Instant revokedAt, @Nullable Instant lastUsedAt, UUID serviceAccountId,
                        UUID serviceAccountPrincipalId, String serviceAccountName, boolean serviceAccountActive,
                        UUID workspaceId, Set<String> permissions, List<String> allowedNetworks) {
        /**
         * Validates and copies.
         */
        public ApiKeyRecord {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(keyPrefix, "keyPrefix");
            Objects.requireNonNull(keyHash, "keyHash");
            Objects.requireNonNull(hashAlgorithm, "hashAlgorithm");
            Objects.requireNonNull(expiresAt, "expiresAt");
            Objects.requireNonNull(serviceAccountId, "serviceAccountId");
            Objects.requireNonNull(serviceAccountPrincipalId, "serviceAccountPrincipalId");
            Objects.requireNonNull(serviceAccountName, "serviceAccountName");
            Objects.requireNonNull(workspaceId, "workspaceId");
            permissions = Set.copyOf(permissions);
            allowedNetworks = List.copyOf(allowedNetworks);
        }

        @Override
        public String toString() {
            return "ApiKeyRecord[id=" + id + ", prefix=" + keyPrefix + ", serviceAccount=" + serviceAccountId + "]";
        }
    }
}
