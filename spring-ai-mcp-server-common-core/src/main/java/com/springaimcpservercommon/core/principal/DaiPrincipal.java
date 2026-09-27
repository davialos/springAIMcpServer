package com.springaimcpservercommon.core.principal;

import com.springaimcpservercommon.annotations.Classification;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The framework's immutable view of the calling identity, produced by an {@code AuthorityMapper} from the host's
 * Spring Security {@code Authentication} (SEC-01 §3). It never contains credentials.
 *
 * @param principalId     id of the {@code dai_principal} row (resolved or created on first sight)
 * @param type            kind of subject
 * @param issuer          IdP issuer (or {@code dai} for service accounts)
 * @param subjectId       stable external subject id (sub / oid / DN), never an e-mail address
 * @param displayName     optional display name
 * @param externalGroups  issuer-qualified group identifiers from the IdP
 * @param globalRoles     platform-wide framework roles
 * @param workspaceRoles  framework roles per workspace id
 * @param attributes      ABAC attributes (tenantId, region, …) — values must be simple types
 * @param clearance       highest data classification the principal may see
 * @param authenticatedAt when the host authenticated the subject (for step-up checks), if known
 * @param scopes          OAuth scopes / API key permissions granted to the current token (empty for sessions)
 */
public record DaiPrincipal(
        UUID principalId,
        SubjectType type,
        String issuer,
        String subjectId,
        @Nullable String displayName,
        Set<String> externalGroups,
        Set<FrameworkRole> globalRoles,
        Map<UUID, Set<FrameworkRole>> workspaceRoles,
        Map<String, Object> attributes,
        Classification clearance,
        @Nullable Instant authenticatedAt,
        Set<String> scopes) {

    /**
     * Validates and defensively copies collections.
     */
    public DaiPrincipal {
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(clearance, "clearance");
        if (clearance == Classification.INHERIT) {
            throw new IllegalArgumentException("clearance must be a concrete classification");
        }
        externalGroups = Set.copyOf(externalGroups);
        globalRoles = Set.copyOf(globalRoles);
        workspaceRoles = Map.copyOf(workspaceRoles);
        workspaceRoles.values().forEach(Objects::requireNonNull);
        attributes = Map.copyOf(attributes);
        scopes = Set.copyOf(scopes);
    }

    /**
     * Whether the principal holds the role globally or in the given workspace.
     *
     * @param role        role to check
     * @param workspaceId workspace, or {@code null} to check global roles only
     * @return {@code true} if the role is held
     */
    public boolean hasRole(FrameworkRole role, @Nullable UUID workspaceId) {
        if (globalRoles.contains(role)) {
            return true;
        }
        if (workspaceId == null) {
            return false;
        }
        Set<FrameworkRole> roles = workspaceRoles.get(workspaceId);
        return roles != null && roles.contains(role);
    }

    /**
     * Whether the principal may see data of the given classification.
     *
     * @param classification effective classification of the data
     * @return {@code true} if within clearance
     */
    public boolean isCleared(Classification classification) {
        return classification != Classification.INHERIT && classification.compareTo(clearance) <= 0;
    }
}
