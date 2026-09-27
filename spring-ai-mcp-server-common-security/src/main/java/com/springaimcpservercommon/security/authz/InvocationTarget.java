package com.springaimcpservercommon.security.authz;

import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.security.permission.Permission;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The secured object of {@link DaiAuthorizationManager}: what is being invoked, independent of the caller.
 *
 * @param permission  required permission
 * @param workspaceId workspace ({@code null} for global permissions; taken from the resource if given)
 * @param resource    resource, if any
 * @param toolName    tool name for tool calls
 * @param draftAccess whether draft (unpublished) resources are acceptable
 */
public record InvocationTarget(Permission permission, @Nullable UUID workspaceId, @Nullable ResourceRef resource,
                               @Nullable String toolName, boolean draftAccess) {

    /**
     * Validates components.
     */
    public InvocationTarget {
        Objects.requireNonNull(permission, "permission");
    }

    /**
     * A target on a resource.
     *
     * @param permission permission
     * @param resource   resource
     * @return the target
     */
    public static InvocationTarget resource(Permission permission, ResourceRef resource) {
        return new InvocationTarget(permission, resource.workspaceId(), resource, null, false);
    }

    /**
     * A tool call target.
     *
     * @param binding  the tool binding resource
     * @param toolName tool name
     * @return the target
     */
    public static InvocationTarget tool(ResourceRef binding, String toolName) {
        return new InvocationTarget(Permission.TOOL_INVOKE, binding.workspaceId(), binding, toolName, false);
    }

    /**
     * Builds the engine request for a caller.
     *
     * @param principal the caller
     * @return the request
     */
    public AuthorizationRequest toRequest(DaiPrincipal principal) {
        return new AuthorizationRequest(principal, permission, workspaceId, resource, toolName, draftAccess, Map.of());
    }
}
