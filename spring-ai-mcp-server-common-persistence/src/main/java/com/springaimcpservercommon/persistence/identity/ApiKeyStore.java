package com.springaimcpservercommon.persistence.identity;

import com.springaimcpservercommon.persistence.unit.DaiStore;
import jakarta.persistence.EntityManager;
import org.hibernate.query.NativeQuery;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service accounts and their API keys (F-65, SEC-01 §9) — the only credentials the framework owns.
 *
 * <p>The store never sees a secret: the security module generates {@code dai_<env>_<keyId>_<secret>}, stores the
 * public prefix and the (peppered) hash here, and on each request looks the key up by prefix with
 * {@link #findActiveByPrefix} and verifies the hash itself.
 */
public final class ApiKeyStore {

    /** Default minimum interval between two {@code last_used_at} updates of the same key. */
    public static final Duration DEFAULT_LAST_USED_THROTTLE = Duration.ofMinutes(5);

    private final DaiStore store;
    private final Clock clock;
    private final Duration lastUsedThrottle;
    private final String insertNetworkSql;
    private final String selectNetworksSql;
    private final String touchSql;

    /**
     * Creates the store with the system UTC clock and the default throttle.
     *
     * @param store the persistence unit
     */
    public ApiKeyStore(DaiStore store) {
        this(store, Clock.systemUTC(), DEFAULT_LAST_USED_THROTTLE);
    }

    /**
     * Creates the store.
     *
     * @param store            the persistence unit
     * @param clock            time source
     * @param lastUsedThrottle minimum interval between {@code last_used_at} updates
     */
    public ApiKeyStore(DaiStore store, Clock clock, Duration lastUsedThrottle) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lastUsedThrottle = Objects.requireNonNull(lastUsedThrottle, "lastUsedThrottle");
        String schema = store.schema();
        this.insertNetworkSql = "INSERT INTO " + schema + ".dai_api_key_allowed_network (api_key_id, network)"
                + " VALUES (?1, CAST(?2 AS cidr))";
        this.selectNetworksSql = "SELECT CAST(network AS text) FROM " + schema + ".dai_api_key_allowed_network"
                + " WHERE api_key_id = ?1 ORDER BY network";
        this.touchSql = "UPDATE " + schema + ".dai_api_key SET last_used_at = ?2"
                + " WHERE id = ?1 AND (last_used_at IS NULL OR last_used_at <= ?3)";
    }

    // ---------------------------------------------------------------------------------------------------------
    // Service accounts
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Creates a service account and its {@code SERVICE_ACCOUNT} principal in one transaction.
     *
     * @param workspaceId owning workspace
     * @param name        name unique within the workspace
     * @param description optional description
     * @param createdBy   creating principal
     * @return the created account
     */
    public ServiceAccountView createServiceAccount(UUID workspaceId, String name, @Nullable String description,
                                                   @Nullable UUID createdBy) {
        ServiceAccount.Created created = ServiceAccount.create(workspaceId, name, description, createdBy, clock.instant());
        return store.transactions().execute(status -> {
            EntityManager em = store.entityManager();
            em.persist(created.principal());
            em.persist(created.account());
            em.flush();
            return created.account().view();
        });
    }

    /**
     * Enables or disables a service account together with its principal. Disabling makes all its keys unusable
     * immediately (subject to the security module's cache TTL).
     *
     * @param id        service account id
     * @param enabled   target state
     * @param updatedBy acting principal
     * @return the account
     * @throws NoSuchElementException if it does not exist
     */
    public ServiceAccountView setServiceAccountEnabled(UUID id, boolean enabled, UUID updatedBy) {
        Instant now = clock.instant();
        return store.transactions().execute(status -> {
            EntityManager em = store.entityManager();
            ServiceAccount account = em.find(ServiceAccount.class, Objects.requireNonNull(id, "id"));
            if (account == null) {
                throw new NoSuchElementException("service account " + id + " does not exist");
            }
            Principal principal = em.find(Principal.class, account.getPrincipalId());
            if (enabled) {
                account.enable(updatedBy, now);
                if (principal != null) {
                    principal.enable();
                }
            } else {
                account.disable(updatedBy, now);
                if (principal != null) {
                    principal.disable();
                }
            }
            return account.view();
        });
    }

    /**
     * Finds a service account.
     *
     * @param id service account id
     * @return the account, if any
     */
    public Optional<ServiceAccountView> findServiceAccount(UUID id) {
        Objects.requireNonNull(id, "id");
        return store.readOnlyTransactions().execute(status ->
                Optional.ofNullable(store.entityManager().find(ServiceAccount.class, id)).map(ServiceAccount::view));
    }

    /**
     * Lists the service accounts of a workspace.
     *
     * @param workspaceId workspace
     * @return accounts ordered by name
     */
    public List<ServiceAccountView> serviceAccounts(UUID workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        return store.readOnlyTransactions().execute(status -> store.entityManager()
                .createQuery("select a from ServiceAccount a where a.workspaceId = :ws order by a.name",
                        ServiceAccount.class)
                .setParameter("ws", workspaceId)
                .getResultStream()
                .map(ServiceAccount::view)
                .toList());
    }

    // ---------------------------------------------------------------------------------------------------------
    // API keys
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Stores a new key with its scopes and optional network allow-list.
     *
     * @param newKey key data (prefix + hash, never the secret)
     * @return the stored key
     * @throws IllegalStateException  if the service account is not active
     * @throws NoSuchElementException if the service account does not exist
     */
    public ApiKeyView createKey(NewApiKey newKey) {
        Objects.requireNonNull(newKey, "newKey");
        Instant now = clock.instant();
        ApiKey key = ApiKey.create(newKey.serviceAccountId(), newKey.keyPrefix(), newKey.keyHash(),
                newKey.hashAlgorithm(), newKey.expiresAt(), newKey.scopes(), newKey.createdBy(), now);
        return store.transactions().execute(status -> {
            EntityManager em = store.entityManager();
            ServiceAccount account = em.find(ServiceAccount.class, newKey.serviceAccountId());
            if (account == null) {
                throw new NoSuchElementException("service account " + newKey.serviceAccountId() + " does not exist");
            }
            if (account.getStatus() != ServiceAccountStatus.ACTIVE) {
                throw new IllegalStateException("service account " + account.getId() + " is disabled");
            }
            em.persist(key);
            em.flush();
            for (String network : newKey.allowedNetworks()) {
                em.createNativeQuery(insertNetworkSql)
                        .setParameter(1, key.getId())
                        .setParameter(2, network)
                        .executeUpdate();
            }
            return view(key, account, networks(em, key.getId()));
        });
    }

    /**
     * Looks up a key that may authenticate now: not revoked, not expired, owning service account and its principal
     * active.
     *
     * @param keyPrefix public prefix parsed from the presented key
     * @return the key with scopes and allow-list, or empty when unknown or unusable
     */
    public Optional<ApiKeyView> findActiveByPrefix(String keyPrefix) {
        Objects.requireNonNull(keyPrefix, "keyPrefix");
        if (!ApiKey.PREFIX.matcher(keyPrefix).matches()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return store.readOnlyTransactions().execute(status -> {
            EntityManager em = store.entityManager();
            List<Object[]> rows = em.createQuery(
                            "select k, a from ApiKey k, ServiceAccount a, Principal p"
                                    + " where k.keyPrefix = :prefix and a.id = k.serviceAccountId and p.id = a.principalId"
                                    + " and k.revokedAt is null and k.expiresAt > :now"
                                    + " and a.status = :accountActive and p.status = :principalActive", Object[].class)
                    .setParameter("prefix", keyPrefix)
                    .setParameter("now", now)
                    .setParameter("accountActive", ServiceAccountStatus.ACTIVE)
                    .setParameter("principalActive", PrincipalStatus.ACTIVE)
                    .getResultList();
            if (rows.isEmpty()) {
                return Optional.<ApiKeyView>empty();
            }
            ApiKey key = (ApiKey) rows.getFirst()[0];
            ServiceAccount account = (ServiceAccount) rows.getFirst()[1];
            return Optional.of(view(key, account, networks(em, key.getId())));
        });
    }

    /**
     * Lists all keys of a service account, including revoked and expired ones.
     *
     * @param serviceAccountId service account
     * @return keys ordered by creation time
     */
    public List<ApiKeyView> keysOf(UUID serviceAccountId) {
        Objects.requireNonNull(serviceAccountId, "serviceAccountId");
        return store.readOnlyTransactions().execute(status -> {
            EntityManager em = store.entityManager();
            ServiceAccount account = em.find(ServiceAccount.class, serviceAccountId);
            if (account == null) {
                return List.<ApiKeyView>of();
            }
            return em.createQuery("select k from ApiKey k where k.serviceAccountId = :sa order by k.createdAt",
                            ApiKey.class)
                    .setParameter("sa", serviceAccountId)
                    .getResultStream()
                    .map(key -> view(key, account, networks(em, key.getId())))
                    .toList();
        });
    }

    /**
     * Revokes a key immediately. Idempotent.
     *
     * @param keyId     key id
     * @param revokedBy acting principal
     * @return {@code true} if this call revoked the key
     */
    public boolean revoke(UUID keyId, UUID revokedBy) {
        Instant now = clock.instant();
        Boolean revoked = store.transactions().execute(status -> {
            ApiKey key = store.entityManager().find(ApiKey.class, Objects.requireNonNull(keyId, "keyId"));
            return key != null && key.revoke(revokedBy, now);
        });
        return Boolean.TRUE.equals(revoked);
    }

    /**
     * Records that a key was used, at most once per throttle interval (one native UPDATE that matches no row when
     * the stored value is recent, so steady traffic does not rewrite the row).
     *
     * @param keyId key id
     * @return {@code true} if {@code last_used_at} was updated
     */
    public boolean touchLastUsed(UUID keyId) {
        Objects.requireNonNull(keyId, "keyId");
        Instant now = clock.instant();
        Integer updated = store.transactions().execute(status -> {
            NativeQuery<?> query = store.entityManager().createNativeQuery(touchSql).unwrap(NativeQuery.class);
            query.setParameter(1, keyId, UUID.class);
            query.setParameter(2, now, Instant.class);
            query.setParameter(3, now.minus(lastUsedThrottle), Instant.class);
            return query.executeUpdate();
        });
        return updated != null && updated > 0;
    }

    private List<String> networks(EntityManager em, UUID keyId) {
        List<?> rows = em.createNativeQuery(selectNetworksSql).setParameter(1, keyId).getResultList();
        return rows.stream().map(String::valueOf).toList();
    }

    private static ApiKeyView view(ApiKey key, ServiceAccount account, List<String> networks) {
        return new ApiKeyView(key.getId(), key.getServiceAccountId(), account.getPrincipalId(), account.getWorkspaceId(),
                key.getKeyPrefix(), key.getKeyHash(), key.getHashAlgorithm(), key.getExpiresAt(), key.getLastUsedAt(),
                key.getRevokedAt(), key.getScopes(), networks);
    }
}
