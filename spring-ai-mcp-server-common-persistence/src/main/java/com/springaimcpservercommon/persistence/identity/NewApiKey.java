package com.springaimcpservercommon.persistence.identity;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Input for {@link ApiKeyStore#createKey}.
 *
 * @param serviceAccountId owning service account
 * @param keyPrefix        public lookup prefix ({@code dai_<env>_<keyId>})
 * @param keyHash          hash of the secret part
 * @param hashAlgorithm    algorithm of {@code keyHash}
 * @param expiresAt        mandatory expiry
 * @param scopes           permissions of the key
 * @param allowedNetworks  optional CIDR allow-list (IPv4/IPv6, e.g. {@code 10.0.0.0/8}); empty = any network.
 *                         PostgreSQL rejects networks with host bits set
 * @param createdBy        creating principal
 */
public record NewApiKey(
        UUID serviceAccountId,
        String keyPrefix,
        String keyHash,
        ApiKeyHashAlgorithm hashAlgorithm,
        Instant expiresAt,
        Set<String> scopes,
        List<String> allowedNetworks,
        @Nullable UUID createdBy) {

    private static final Pattern CIDR_CHARS = Pattern.compile("[0-9A-Fa-f:.]{2,45}(/[0-9]{1,3})?");

    /** Validates and copies collections. */
    public NewApiKey {
        Objects.requireNonNull(serviceAccountId, "serviceAccountId");
        Objects.requireNonNull(keyPrefix, "keyPrefix");
        Objects.requireNonNull(keyHash, "keyHash");
        Objects.requireNonNull(hashAlgorithm, "hashAlgorithm");
        Objects.requireNonNull(expiresAt, "expiresAt");
        scopes = Set.copyOf(scopes);
        allowedNetworks = List.copyOf(allowedNetworks);
        for (String network : allowedNetworks) {
            if (!CIDR_CHARS.matcher(network).matches()) {
                throw new IllegalArgumentException("invalid network: " + network);
            }
        }
    }

    @Override
    public String toString() {
        return "NewApiKey[serviceAccountId=" + serviceAccountId + ", keyPrefix=" + keyPrefix + "]";
    }
}
