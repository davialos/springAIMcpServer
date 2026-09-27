package com.springaimcpservercommon.security.authz;

import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.security.permission.Permission;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Input of one authorization decision.
 *
 * @param principal         the caller
 * @param permission        requested permission
 * @param workspaceId       workspace scope ({@code null} = global check); equals the resource's workspace if a
 *                          resource is given
 * @param resource          resource, if the decision is about one
 * @param toolName          tool name for tool calls (kill switches may target it)
 * @param draftAccess       whether a not-yet-published resource is acceptable (playground on drafts)
 * @param requestAttributes trusted request facts for ABAC conditions ({@code request.*}), never model-supplied
 */
public record AuthorizationRequest(
        DaiPrincipal principal,
        Permission permission,
        @Nullable UUID workspaceId,
        @Nullable ResourceRef resource,
        @Nullable String toolName,
        boolean draftAccess,
        Map<String, Object> requestAttributes) {

    /**
     * Validates consistency.
     */
    public AuthorizationRequest {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(permission, "permission");
        if (resource != null) {
            if (workspaceId != null && !workspaceId.equals(resource.workspaceId())) {
                throw new IllegalArgumentException("workspaceId differs from the resource's workspace");
            }
            workspaceId = resource.workspaceId();
        }
        requestAttributes = Collections.unmodifiableMap(new LinkedHashMap<>(requestAttributes));
    }

    /**
     * A decision about a resource.
     *
     * @param principal  caller
     * @param permission permission
     * @param resource   resource
     * @return the request
     */
    public static AuthorizationRequest onResource(DaiPrincipal principal, Permission permission, ResourceRef resource) {
        return new AuthorizationRequest(principal, permission, resource.workspaceId(), resource, null, false, Map.of());
    }

    /**
     * A workspace-level decision (e.g. {@code endpoint:author} in a workspace).
     *
     * @param principal   caller
     * @param permission  permission
     * @param workspaceId workspace
     * @return the request
     */
    public static AuthorizationRequest onWorkspace(DaiPrincipal principal, Permission permission, UUID workspaceId) {
        return new AuthorizationRequest(principal, permission, Objects.requireNonNull(workspaceId, "workspaceId"), null,
                null, false, Map.of());
    }

    /**
     * A global decision (only global roles apply).
     *
     * @param principal  caller
     * @param permission permission
     * @return the request
     */
    public static AuthorizationRequest global(DaiPrincipal principal, Permission permission) {
        return new AuthorizationRequest(principal, permission, null, null, null, false, Map.of());
    }

    /**
     * Returns a copy for a tool call.
     *
     * @param name tool name
     * @return new request
     */
    public AuthorizationRequest withToolName(String name) {
        return new AuthorizationRequest(principal, permission, workspaceId, resource, name, draftAccess, requestAttributes);
    }

    /**
     * Returns a copy that accepts unpublished (draft) resources.
     *
     * @return new request
     */
    public AuthorizationRequest withDraftAccess() {
        return new AuthorizationRequest(principal, permission, workspaceId, resource, toolName, true, requestAttributes);
    }

    /**
     * Returns a copy with trusted request attributes.
     *
     * @param attributes attributes
     * @return new request
     */
    public AuthorizationRequest withRequestAttributes(Map<String, Object> attributes) {
        return new AuthorizationRequest(principal, permission, workspaceId, resource, toolName, draftAccess, attributes);
    }
}
