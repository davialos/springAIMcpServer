package com.springaimcpservercommon.persistence.identity;

import com.springaimcpservercommon.core.id.Ids;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * API key of a service account ({@code dai_api_key}, SEC-01 §9). Only the public prefix and a hash of the secret are
 * stored; generating the secret and hashing it with the server pepper is the security module's job.
 *
 * <p>Scopes ({@code dai_api_key_scope}) are an element collection. The optional network allow-list
 * ({@code dai_api_key_allowed_network}, PostgreSQL {@code cidr}) is written and read by {@link ApiKeyStore} with
 * native SQL, because binding a {@code cidr} needs an explicit cast that element-collection deletes cannot apply;
 * the list is immutable after creation, so no mapping is needed here.
 */
@Entity
@Table(name = "dai_api_key")
public class ApiKey {

    /** Format of the public, unique lookup part of a key ({@code dai_<env>_<keyId>}). */
    public static final Pattern PREFIX = Pattern.compile("dai_[a-z]+_[A-Za-z0-9]{8,32}");

    /** Format of a permission scope ({@code resource:action}). */
    public static final Pattern PERMISSION = Pattern.compile("[a-z][a-z-]*:[a-z][a-z-]*");

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "service_account_id", nullable = false, updatable = false)
    private UUID serviceAccountId;

    @Column(name = "key_prefix", nullable = false, updatable = false)
    private String keyPrefix;

    @Column(name = "key_hash", nullable = false, updatable = false)
    private String keyHash;

    @Convert(converter = ApiKeyHashAlgorithm.DbConverter.class)
    @Column(name = "hash_algorithm", nullable = false, updatable = false)
    private ApiKeyHashAlgorithm hashAlgorithm;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private @Nullable Instant lastUsedAt;

    @Column(name = "revoked_at")
    private @Nullable Instant revokedAt;

    @Column(name = "revoked_by")
    private @Nullable UUID revokedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private @Nullable UUID createdBy;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "dai_api_key_scope", joinColumns = @JoinColumn(name = "api_key_id"))
    @Column(name = "permission", nullable = false)
    private Set<String> scopes = new HashSet<>();

    /** For JPA only. */
    protected ApiKey() {
    }

    private ApiKey(UUID serviceAccountId, String keyPrefix, String keyHash, ApiKeyHashAlgorithm hashAlgorithm,
                   Instant expiresAt, Set<String> scopes, @Nullable UUID createdBy, Instant now) {
        this.id = Ids.newId();
        this.serviceAccountId = serviceAccountId;
        this.keyPrefix = keyPrefix;
        this.keyHash = keyHash;
        this.hashAlgorithm = hashAlgorithm;
        this.expiresAt = expiresAt;
        this.scopes = new HashSet<>(scopes);
        this.createdAt = now;
        this.createdBy = createdBy;
    }

    /**
     * Creates a key record.
     *
     * @param serviceAccountId owning service account
     * @param keyPrefix        public lookup prefix, see {@link #PREFIX}
     * @param keyHash          hash of the secret part (never the secret itself)
     * @param hashAlgorithm    algorithm of {@code keyHash}
     * @param expiresAt        mandatory expiry, after {@code now}
     * @param scopes           permissions granted to the key (subset of the account's permissions), may be empty
     * @param createdBy        creating principal
     * @param now              creation time
     * @return the new key (not yet persisted)
     */
    public static ApiKey create(UUID serviceAccountId, String keyPrefix, String keyHash,
                                ApiKeyHashAlgorithm hashAlgorithm, Instant expiresAt, Set<String> scopes,
                                @Nullable UUID createdBy, Instant now) {
        Objects.requireNonNull(serviceAccountId, "serviceAccountId");
        Objects.requireNonNull(keyPrefix, "keyPrefix");
        Objects.requireNonNull(keyHash, "keyHash");
        Objects.requireNonNull(hashAlgorithm, "hashAlgorithm");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(now, "now");
        if (!PREFIX.matcher(keyPrefix).matches()) {
            throw new IllegalArgumentException("API key prefix must match dai_<env>_<keyId>");
        }
        if (keyHash.isBlank()) {
            throw new IllegalArgumentException("keyHash must not be blank");
        }
        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("API key expiry must be in the future");
        }
        for (String scope : scopes) {
            if (!PERMISSION.matcher(scope).matches()) {
                throw new IllegalArgumentException("invalid API key scope: " + scope);
            }
        }
        return new ApiKey(serviceAccountId, keyPrefix, keyHash, hashAlgorithm, expiresAt, scopes, createdBy, now);
    }

    /**
     * Revokes the key. Idempotent: the first revocation wins.
     *
     * @param by  acting principal
     * @param now revocation time
     * @return {@code true} if this call revoked the key
     */
    public boolean revoke(UUID by, Instant now) {
        if (revokedAt != null) {
            return false;
        }
        this.revokedAt = Objects.requireNonNull(now, "now");
        this.revokedBy = Objects.requireNonNull(by, "by");
        return true;
    }

    /**
     * Whether the key may authenticate at the given time (not revoked, not expired). The owning service account and
     * principal status are checked by {@link ApiKeyStore#findActiveByPrefix}.
     *
     * @param now evaluation time
     * @return {@code true} when usable
     */
    public boolean isUsableAt(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public UUID getId() {
        return id;
    }

    public UUID getServiceAccountId() {
        return serviceAccountId;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public ApiKeyHashAlgorithm getHashAlgorithm() {
        return hashAlgorithm;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public @Nullable Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public @Nullable Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Scopes of the key (read-only copy).
     *
     * @return the scopes
     */
    public Set<String> getScopes() {
        return Set.copyOf(scopes);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof ApiKey other && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
