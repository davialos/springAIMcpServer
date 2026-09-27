package com.springaimcpservercommon.security.authz;

import com.springaimcpservercommon.security.permission.Permission;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Helper for method security in our own controllers, registered by autoconfigure under the namespaced bean name
 * {@value #BEAN_NAME}:
 * <pre>{@code @PreAuthorize("@daiAuthz.can(authentication, 'endpoint:author', #workspaceId)")}</pre>
 * Unknown permissions, malformed workspace ids and unauthenticated callers yield {@code false} (fail closed).
 */
public final class DaiAuthorization {

    /** Bean name used in SpEL expressions. */
    public static final String BEAN_NAME = "daiAuthz";

    private static final Logger log = LoggerFactory.getLogger(DaiAuthorization.class);

    private final DaiAuthorizationManager manager;

    /**
     * Creates the helper.
     *
     * @param manager authorization manager
     */
    public DaiAuthorization(DaiAuthorizationManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    /**
     * Global permission check (global roles only).
     *
     * @param authentication current authentication
     * @param permission     permission value, e.g. {@code rolemapping:manage}
     * @return {@code true} if permitted
     */
    public boolean can(@Nullable Authentication authentication, String permission) {
        return can(authentication, permission, null);
    }

    /**
     * Workspace permission check.
     *
     * @param authentication current authentication
     * @param permission     permission value, e.g. {@code endpoint:author}
     * @param workspace      workspace id ({@link UUID} or its string form), {@code null} for a global check
     * @return {@code true} if permitted
     */
    public boolean can(@Nullable Authentication authentication, String permission, @Nullable Object workspace) {
        Optional<Permission> resolved = Permission.fromValue(permission);
        if (resolved.isEmpty()) {
            log.warn("unknown permission '{}' in authorization expression: denied", permission);
            return false;
        }
        UUID workspaceId = null;
        if (workspace != null) {
            workspaceId = toUuid(workspace);
            if (workspaceId == null) {
                return false;
            }
        }
        return manager.decide(authentication, new InvocationTarget(resolved.get(), workspaceId, null, null, false))
                .granted();
    }

    /**
     * Resource permission check.
     *
     * @param authentication current authentication
     * @param permission     permission value
     * @param resource       resource
     * @return {@code true} if permitted
     */
    public boolean canOn(@Nullable Authentication authentication, String permission, ResourceRef resource) {
        return Permission.fromValue(permission)
                .map(p -> manager.decide(authentication, InvocationTarget.resource(p, resource)).granted())
                .orElse(false);
    }

    private static @Nullable UUID toUuid(Object workspace) {
        if (workspace instanceof UUID uuid) {
            return uuid;
        }
        try {
            return UUID.fromString(workspace.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
