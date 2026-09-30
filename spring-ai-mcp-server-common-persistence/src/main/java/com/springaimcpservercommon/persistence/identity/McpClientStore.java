package com.springaimcpservercommon.persistence.identity;

import com.springaimcpservercommon.persistence.unit.DaiStore;
import jakarta.persistence.EntityManager;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Approved MCP clients per workspace and per-user consent (LLD-07 §5.3).
 */
public final class McpClientStore {

    private final DaiStore store;
    private final Clock clock;

    /**
     * Creates the store with the system UTC clock.
     *
     * @param store the persistence unit
     */
    public McpClientStore(DaiStore store) {
        this(store, Clock.systemUTC());
    }

    /**
     * Creates the store.
     *
     * @param store the persistence unit
     * @param clock time source
     */
    public McpClientStore(DaiStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Registers a client for a workspace in PENDING state.
     *
     * @param workspaceId      workspace
     * @param issuer           authorization server issuer
     * @param clientId         OAuth client id
     * @param displayName      display name
     * @param registrationType registration type
     * @param createdBy        registering principal
     * @return the registration
     */
    public McpClientView register(UUID workspaceId, String issuer, String clientId, String displayName,
                                  McpRegistrationType registrationType, UUID createdBy) {
        McpClient client = McpClient.register(workspaceId, issuer, clientId, displayName, registrationType, createdBy,
                clock.instant());
        return store.transactions().execute(status -> {
            EntityManager em = store.entityManager();
            em.persist(client);
            em.flush();
            return client.view();
        });
    }

    /**
     * Approves a pending client.
     *
     * @param id         registration id
     * @param approvedBy approving principal
     * @return the approved registration
     */
    public McpClientView approve(UUID id, UUID approvedBy) {
        Instant now = clock.instant();
        return store.transactions().execute(status -> {
            McpClient client = require(store.entityManager(), id);
            client.approve(approvedBy, now);
            store.entityManager().flush();
            return client.view();
        });
    }

    /**
     * Revokes a client and all its active consents.
     *
     * @param id registration id
     * @return {@code true} if the client was not revoked before
     */
    public boolean revoke(UUID id) {
        Instant now = clock.instant();
        Boolean revoked = store.transactions().execute(status -> {
            EntityManager em = store.entityManager();
            McpClient client = require(em, id);
            activeConsents(em, id).forEach(consent -> consent.revoke(now));
            return client.revoke(now);
        });
        return Boolean.TRUE.equals(revoked);
    }

    /**
     * Who registered a client (for segregation of duties on approval).
     *
     * @param id registration id
     * @return the registering principal, if the registration exists
     */
    public Optional<UUID> registeredBy(UUID id) {
        return store.transactions().execute(status ->
                Optional.ofNullable(store.entityManager().find(McpClient.class, id)).map(McpClient::createdBy));
    }

    /**
     * Finds a registration.
     *
     * @param id registration id
     * @return the registration, if any
     */
    public Optional<McpClientView> find(UUID id) {
        Objects.requireNonNull(id, "id");
        return store.readOnlyTransactions().execute(status ->
                Optional.ofNullable(store.entityManager().find(McpClient.class, id)).map(McpClient::view));
    }

    /**
     * Approved registrations of a client (one per workspace that approved it), served by {@code ix_mcp_client_lookup}.
     *
     * @param issuer   authorization server issuer
     * @param clientId OAuth client id
     * @return approved registrations
     */
    public List<McpClientView> findApproved(String issuer, String clientId) {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(clientId, "clientId");
        return store.readOnlyTransactions().execute(status -> store.entityManager()
                .createQuery("select c from McpClient c where c.issuer = :issuer and c.clientId = :clientId"
                        + " and c.status = :approved order by c.createdAt", McpClient.class)
                .setParameter("issuer", issuer)
                .setParameter("clientId", clientId)
                .setParameter("approved", McpClientStatus.APPROVED)
                .getResultStream()
                .map(McpClient::view)
                .toList());
    }

    /**
     * All registrations of a workspace.
     *
     * @param workspaceId workspace
     * @return registrations ordered by creation time
     */
    public List<McpClientView> listByWorkspace(UUID workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        return store.readOnlyTransactions().execute(status -> store.entityManager()
                .createQuery("select c from McpClient c where c.workspaceId = :ws order by c.createdAt", McpClient.class)
                .setParameter("ws", workspaceId)
                .getResultStream()
                .map(McpClient::view)
                .toList());
    }

    /**
     * Records a user's consent. An existing active consent of the same user for the client is revoked first (and
     * flushed, so {@code uq_mcp_client_consent_active} holds), keeping the history.
     *
     * @param mcpClientId approved client registration
     * @param principalId consenting principal
     * @param scopes      consented scopes
     * @return the new active consent
     */
    public McpConsentView grantConsent(UUID mcpClientId, UUID principalId, Set<McpConsentScope> scopes) {
        Objects.requireNonNull(scopes, "scopes");
        Instant now = clock.instant();
        return store.transactions().execute(status -> {
            EntityManager em = store.entityManager();
            McpClient client = require(em, mcpClientId);
            McpClientConsent consent = McpClientConsent.grant(client, principalId, scopes, now);
            activeConsent(em, mcpClientId, principalId).ifPresent(previous -> {
                previous.revoke(now);
                em.flush();
            });
            em.persist(consent);
            em.flush();
            return consent.view();
        });
    }

    /**
     * Revokes a user's active consent.
     *
     * @param mcpClientId client registration
     * @param principalId principal
     * @return {@code true} if an active consent was revoked
     */
    public boolean revokeConsent(UUID mcpClientId, UUID principalId) {
        Instant now = clock.instant();
        Boolean revoked = store.transactions().execute(status -> {
            Optional<McpClientConsent> consent = activeConsent(store.entityManager(), mcpClientId, principalId);
            consent.ifPresent(c -> c.revoke(now));
            return consent.isPresent();
        });
        return Boolean.TRUE.equals(revoked);
    }

    /**
     * The active consent of a user for a client, if the client is still approved.
     *
     * @param mcpClientId client registration
     * @param principalId principal
     * @return the active consent
     */
    public Optional<McpConsentView> activeConsent(UUID mcpClientId, UUID principalId) {
        return store.readOnlyTransactions().execute(status -> {
            EntityManager em = store.entityManager();
            McpClient client = em.find(McpClient.class, Objects.requireNonNull(mcpClientId, "mcpClientId"));
            if (client == null || client.getStatus() != McpClientStatus.APPROVED) {
                return Optional.<McpConsentView>empty();
            }
            return activeConsent(em, mcpClientId, principalId).map(McpClientConsent::view);
        });
    }

    private static Optional<McpClientConsent> activeConsent(EntityManager em, UUID mcpClientId, UUID principalId) {
        return em.createQuery("select c from McpClientConsent c where c.mcpClientId = :client"
                        + " and c.principalId = :principal and c.revokedAt is null", McpClientConsent.class)
                .setParameter("client", Objects.requireNonNull(mcpClientId, "mcpClientId"))
                .setParameter("principal", Objects.requireNonNull(principalId, "principalId"))
                .getResultStream()
                .findFirst();
    }

    private static List<McpClientConsent> activeConsents(EntityManager em, UUID mcpClientId) {
        return em.createQuery("select c from McpClientConsent c where c.mcpClientId = :client and c.revokedAt is null",
                        McpClientConsent.class)
                .setParameter("client", mcpClientId)
                .getResultList();
    }

    private static McpClient require(EntityManager em, UUID id) {
        McpClient client = em.find(McpClient.class, Objects.requireNonNull(id, "id"));
        if (client == null) {
            throw new NoSuchElementException("MCP client registration " + id + " does not exist");
        }
        return client;
    }
}
