package com.springaimcpservercommon.security.apikey;

import java.time.Instant;
import java.util.Objects;

/**
 * A freshly generated API key. {@link #plaintext()} is shown to the user exactly once and must never be stored or
 * logged; only {@link #keyPrefix()}, {@link #keyHash()}, {@link #hashAlgorithm()} and {@link #expiresAt()} are
 * persisted.
 *
 * @param plaintext     the full key {@code dai_<env>_<keyId>_<secret>}
 * @param keyPrefix     public prefix ({@code dai_api_key.key_prefix})
 * @param keyHash       {@code dai_api_key.key_hash}
 * @param hashAlgorithm {@code dai_api_key.hash_algorithm}
 * @param expiresAt     {@code dai_api_key.expires_at}
 */
public record GeneratedApiKey(String plaintext, String keyPrefix, String keyHash, String hashAlgorithm, Instant expiresAt) {

    /**
     * Validates components.
     */
    public GeneratedApiKey {
        Objects.requireNonNull(plaintext, "plaintext");
        Objects.requireNonNull(keyPrefix, "keyPrefix");
        Objects.requireNonNull(keyHash, "keyHash");
        Objects.requireNonNull(hashAlgorithm, "hashAlgorithm");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    @Override
    public String toString() {
        return "GeneratedApiKey[prefix=" + keyPrefix + ", expiresAt=" + expiresAt + ", plaintext=***]";
    }
}
