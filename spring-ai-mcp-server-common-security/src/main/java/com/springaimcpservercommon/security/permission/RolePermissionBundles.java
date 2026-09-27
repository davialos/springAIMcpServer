package com.springaimcpservercommon.security.permission;

import com.springaimcpservercommon.core.principal.FrameworkRole;

import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.springaimcpservercommon.security.permission.Permission.AGENT_AUTHOR;
import static com.springaimcpservercommon.security.permission.Permission.AGENT_PLAYGROUND;
import static com.springaimcpservercommon.security.permission.Permission.AGENT_PUBLISH;
import static com.springaimcpservercommon.security.permission.Permission.AUDIT_READ;
import static com.springaimcpservercommon.security.permission.Permission.BUDGET_MANAGE;
import static com.springaimcpservercommon.security.permission.Permission.CATALOG_ANNOTATE;
import static com.springaimcpservercommon.security.permission.Permission.CATALOG_DECLASSIFY;
import static com.springaimcpservercommon.security.permission.Permission.CATALOG_READ;
import static com.springaimcpservercommon.security.permission.Permission.DATA_WRITE_APPROVE;
import static com.springaimcpservercommon.security.permission.Permission.ENDPOINT_AUTHOR;
import static com.springaimcpservercommon.security.permission.Permission.ENDPOINT_PUBLISH;
import static com.springaimcpservercommon.security.permission.Permission.GRANT_MANAGE;
import static com.springaimcpservercommon.security.permission.Permission.OPS_KILLSWITCH;
import static com.springaimcpservercommon.security.permission.Permission.QUERY_AUTHOR;
import static com.springaimcpservercommon.security.permission.Permission.QUERY_EXPLAIN;
import static com.springaimcpservercommon.security.permission.Permission.QUERY_PREVIEW;
import static com.springaimcpservercommon.security.permission.Permission.QUERY_PUBLISH;
import static com.springaimcpservercommon.security.permission.Permission.REVIEW_APPROVE;
import static com.springaimcpservercommon.security.permission.Permission.ROLEMAPPING_MANAGE;
import static com.springaimcpservercommon.security.permission.Permission.SERVICEACCOUNT_MANAGE;
import static com.springaimcpservercommon.security.permission.Permission.TOOL_AUTHOR;
import static com.springaimcpservercommon.security.permission.Permission.TOOL_PUBLISH;
import static com.springaimcpservercommon.security.permission.Permission.WORKSPACE_ADMIN;

/**
 * Immutable role → permission bundles (SEC-01 §5). The defaults reproduce the SEC-01 matrix; hosts may override a
 * role's bundle through configuration ({@link #withBundle}). Bundles are data, permissions are code.
 *
 * <p>Only {@link Permission.Kind#ROLE} permissions may appear in bundles: invocation and write-proposal permissions are
 * "via grant" in SEC-01 and stay default-deny, so an override can never turn them into ambient role rights.
 */
public final class RolePermissionBundles {

    private static final Set<Permission> AUTHORING = EnumSet.of(ENDPOINT_AUTHOR, QUERY_AUTHOR, AGENT_AUTHOR, TOOL_AUTHOR);
    private static final Set<Permission> PUBLISHING = EnumSet.of(ENDPOINT_PUBLISH, QUERY_PUBLISH, AGENT_PUBLISH, TOOL_PUBLISH);

    private static final RolePermissionBundles DEFAULTS = new RolePermissionBundles(defaultMap());

    private final Map<FrameworkRole, Set<Permission>> bundles;

    private RolePermissionBundles(Map<FrameworkRole, Set<Permission>> bundles) {
        EnumMap<FrameworkRole, Set<Permission>> copy = new EnumMap<>(FrameworkRole.class);
        for (FrameworkRole role : FrameworkRole.values()) {
            Set<Permission> permissions = bundles.getOrDefault(role, Set.of());
            validate(role, permissions);
            copy.put(role, permissions.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(permissions)));
        }
        this.bundles = Map.copyOf(copy);
    }

    /**
     * Returns the SEC-01 §5 default bundles.
     *
     * @return default bundles
     */
    public static RolePermissionBundles defaults() {
        return DEFAULTS;
    }

    /**
     * Creates bundles from explicit data, e.g. configuration; roles not present get an empty bundle.
     *
     * @param bundles role → permissions
     * @return validated bundles
     * @throws IllegalArgumentException if a bundle contains a grant-only or key-scope permission
     */
    public static RolePermissionBundles of(Map<FrameworkRole, ? extends Collection<Permission>> bundles) {
        EnumMap<FrameworkRole, Set<Permission>> map = new EnumMap<>(FrameworkRole.class);
        bundles.forEach((role, permissions) -> map.put(role, Set.copyOf(permissions)));
        return new RolePermissionBundles(map);
    }

    /**
     * Returns a copy in which one role's bundle is replaced.
     *
     * @param role        role to override
     * @param permissions new bundle
     * @return new bundles
     * @throws IllegalArgumentException if the bundle contains a grant-only or key-scope permission
     */
    public RolePermissionBundles withBundle(FrameworkRole role, Collection<Permission> permissions) {
        EnumMap<FrameworkRole, Set<Permission>> map = new EnumMap<>(bundles);
        map.put(Objects.requireNonNull(role, "role"), Set.copyOf(permissions));
        return new RolePermissionBundles(map);
    }

    /**
     * Returns the bundle of a role.
     *
     * @param role the role
     * @return the permissions (never {@code null})
     */
    public Set<Permission> permissionsOf(FrameworkRole role) {
        return bundles.get(role);
    }

    /**
     * Whether a role's bundle contains a permission.
     *
     * @param role       the role
     * @param permission the permission
     * @return {@code true} if granted by the bundle
     */
    public boolean grants(FrameworkRole role, Permission permission) {
        return bundles.get(role).contains(permission);
    }

    /**
     * Returns all bundles.
     *
     * @return unmodifiable role → permissions map
     */
    public Map<FrameworkRole, Set<Permission>> asMap() {
        return bundles;
    }

    private static void validate(FrameworkRole role, Set<Permission> permissions) {
        for (Permission permission : permissions) {
            if (permission.kind() != Permission.Kind.ROLE) {
                throw new IllegalArgumentException("permission " + permission + " of role " + role
                        + " can only be held through a grant or API key scope, not a role bundle");
            }
        }
    }

    private static Map<FrameworkRole, Set<Permission>> defaultMap() {
        EnumMap<FrameworkRole, Set<Permission>> map = new EnumMap<>(FrameworkRole.class);

        Set<Permission> platformAdmin = EnumSet.of(CATALOG_READ, CATALOG_ANNOTATE, CATALOG_DECLASSIFY, WORKSPACE_ADMIN,
                QUERY_EXPLAIN, GRANT_MANAGE, BUDGET_MANAGE, OPS_KILLSWITCH);
        platformAdmin.addAll(PUBLISHING);
        map.put(FrameworkRole.PLATFORM_ADMIN, platformAdmin);

        map.put(FrameworkRole.SECURITY_ADMIN, EnumSet.of(ROLEMAPPING_MANAGE, SERVICEACCOUNT_MANAGE, AUDIT_READ));

        map.put(FrameworkRole.AUDITOR, EnumSet.of(AUDIT_READ));

        Set<Permission> owner = EnumSet.of(CATALOG_READ, CATALOG_ANNOTATE, WORKSPACE_ADMIN, QUERY_PREVIEW, AGENT_PLAYGROUND,
                QUERY_EXPLAIN, GRANT_MANAGE, SERVICEACCOUNT_MANAGE, BUDGET_MANAGE, OPS_KILLSWITCH, AUDIT_READ);
        owner.addAll(AUTHORING);
        owner.addAll(PUBLISHING);
        map.put(FrameworkRole.WORKSPACE_OWNER, owner);

        Set<Permission> author = EnumSet.of(CATALOG_READ, QUERY_PREVIEW, AGENT_PLAYGROUND, QUERY_EXPLAIN);
        author.addAll(AUTHORING);
        map.put(FrameworkRole.AUTHOR, author);

        Set<Permission> approver = EnumSet.of(CATALOG_READ, QUERY_PREVIEW, AGENT_PLAYGROUND, REVIEW_APPROVE, DATA_WRITE_APPROVE);
        approver.addAll(PUBLISHING);
        map.put(FrameworkRole.APPROVER, approver);

        map.put(FrameworkRole.OPERATOR, EnumSet.of(OPS_KILLSWITCH));
        map.put(FrameworkRole.CONSUMER, EnumSet.noneOf(Permission.class));
        return map;
    }
}
