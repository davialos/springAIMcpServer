package com.springaimcpservercommon.security.port;

import com.springaimcpservercommon.core.principal.FrameworkRole;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Port to {@code dai_workspace_member}: direct workspace roles of principals (users, groups, service accounts).
 */
public interface MembershipSource {

    /**
     * Returns the union of workspace roles held by any of the given principals, ignoring memberships that expired
     * at {@code at} and memberships of archived workspaces.
     *
     * @param principalIds the user/service-account principal id plus the ids of its group principals
     * @param at           evaluation time
     * @return workspace id → roles (never containing global-only roles)
     */
    Map<UUID, Set<FrameworkRole>> findWorkspaceRoles(Set<UUID> principalIds, Instant at);
}
