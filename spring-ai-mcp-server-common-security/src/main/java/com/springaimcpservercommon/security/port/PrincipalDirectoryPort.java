package com.springaimcpservercommon.security.port;

import com.springaimcpservercommon.core.principal.SubjectType;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Port to {@code dai_principal}: compact references to subjects owned by the host IdP (ADR-0005). Implemented by
 * autoconfigure on top of the persistence store.
 *
 * <p>Implementations must be safe for concurrent calls and idempotent: two nodes resolving the same new subject at the
 * same time must end up with the same id ({@code uq_principal_subject (issuer, subject_type, external_id)}).
 */
public interface PrincipalDirectoryPort {

    /**
     * Returns the id of the principal row for a subject, creating it on first sight and refreshing
     * {@code last_seen_at} (implementations may throttle that refresh).
     *
     * @param type        subject type
     * @param issuer      issuer ({@code https://…} OIDC issuer, {@code ldap:…}, {@code saml:…}, {@code host}, {@code dai})
     * @param externalId  stable external id (sub / oid / DN / hashed id), never an e-mail address, 1..512 chars
     * @param displayName optional display name (may be updated)
     * @return the principal id
     */
    UUID resolvePrincipalId(SubjectType type, String issuer, String externalId, @Nullable String displayName);

    /**
     * Looks up existing principals without creating them (used for group principals: a group only has a row when it
     * is referenced by a membership or grant).
     *
     * @param type        subject type
     * @param issuer      issuer
     * @param externalIds external ids to look up
     * @return external id → principal id for the rows that exist and are {@code ACTIVE}
     */
    Map<String, UUID> findPrincipalIds(SubjectType type, String issuer, Set<String> externalIds);
}
