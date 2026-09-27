package com.springaimcpservercommon.security.permission;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Permission catalog (SEC-01 §5). Permissions are code constants; which role holds which permission is data
 * ({@link RolePermissionBundles}).
 *
 * <p>The textual {@link #value()} is {@code <resource>:<action>} and is exactly what is stored in
 * {@code dai_grant.permission} and {@code dai_api_key_scope.permission} (both constrained to
 * {@code ^[a-z][a-z-]*:[a-z][a-z-]*$}). The {@code *:publish} and {@code endpoint/query/agent/tool:author} rows of the
 * SEC-01 matrix are expanded to one constant per resource kind because the store does not allow wildcards.
 */
public enum Permission {

    /** Browse the effective catalog. */
    CATALOG_READ("catalog:read", Kind.ROLE),
    /** Maintain catalog policy overlays. */
    CATALOG_ANNOTATE("catalog:annotate", Kind.ROLE),
    /** Lower a classification; the approval workflow additionally requires a second admin (SEC-01 §5 footnote *). */
    CATALOG_DECLASSIFY("catalog:declassify", Kind.ROLE),
    /** Manage a workspace (members, settings). */
    WORKSPACE_ADMIN("workspace:admin", Kind.ROLE),
    /** Create and edit endpoint drafts. */
    ENDPOINT_AUTHOR("endpoint:author", Kind.ROLE),
    /** Create and edit query drafts. */
    QUERY_AUTHOR("query:author", Kind.ROLE),
    /** Create and edit agent drafts. */
    AGENT_AUTHOR("agent:author", Kind.ROLE),
    /** Create and edit tool binding drafts. */
    TOOL_AUTHOR("tool:author", Kind.ROLE),
    /** Preview query results while authoring. */
    QUERY_PREVIEW("query:preview", Kind.ROLE),
    /** Use the agent playground. */
    AGENT_PLAYGROUND("agent:playground", Kind.ROLE),
    /** See the generated SQL / explain plan of a query. */
    QUERY_EXPLAIN("query:explain", Kind.ROLE),
    /** Approve or reject a submitted revision. */
    REVIEW_APPROVE("review:approve", Kind.ROLE),
    /** Publish an endpoint revision (publish policy may additionally require an approval). */
    ENDPOINT_PUBLISH("endpoint:publish", Kind.ROLE),
    /** Publish a query revision. */
    QUERY_PUBLISH("query:publish", Kind.ROLE),
    /** Publish an agent revision. */
    AGENT_PUBLISH("agent:publish", Kind.ROLE),
    /** Publish a tool binding revision. */
    TOOL_PUBLISH("tool:publish", Kind.ROLE),
    /** Create, change and revoke grants. */
    GRANT_MANAGE("grant:manage", Kind.ROLE),
    /** Maintain IdP → framework role mappings. */
    ROLEMAPPING_MANAGE("rolemapping:manage", Kind.ROLE),
    /** Maintain service accounts and their API keys. */
    SERVICEACCOUNT_MANAGE("serviceaccount:manage", Kind.ROLE),
    /** Maintain budgets. */
    BUDGET_MANAGE("budget:manage", Kind.ROLE),
    /** Set and clear kill switches. */
    OPS_KILLSWITCH("ops:killswitch", Kind.ROLE),
    /** Second-person approval of a data write proposal (LLD-11). */
    DATA_WRITE_APPROVE("data:write-approve", Kind.ROLE),
    /** Read the audit log (workspace roles: own workspace only). */
    AUDIT_READ("audit:read", Kind.ROLE),

    /** Let an agent/endpoint create write proposals for the caller (grant only). */
    DATA_WRITE_PROPOSE("data:write-propose", Kind.GRANT),
    /** Confirm one's own write proposal (grant only). */
    DATA_WRITE_CONFIRM("data:write-confirm", Kind.GRANT),
    /** Invoke a published dynamic endpoint (grant only). */
    ENDPOINT_INVOKE("endpoint:invoke", Kind.GRANT),
    /** Invoke a published agent (grant only). */
    AGENT_INVOKE("agent:invoke", Kind.GRANT),
    /** Invoke a tool binding (grant only). */
    TOOL_INVOKE("tool:invoke", Kind.GRANT),

    /** API-key equivalent of the MCP scope {@code dai.mcp.read} (API key scopes cannot hold dotted scopes). */
    MCP_READ("mcp:read", Kind.KEY_SCOPE),
    /** API-key equivalent of the MCP scope {@code dai.mcp.propose}. */
    MCP_PROPOSE("mcp:propose", Kind.KEY_SCOPE),
    /** API-key equivalent of the MCP scope {@code dai.mcp.agents}. */
    MCP_AGENTS("mcp:agents", Kind.KEY_SCOPE);

    /**
     * How a permission can be held.
     */
    public enum Kind {
        /** Held through a framework role bundle (and optionally a grant). */
        ROLE,
        /** Held only through an explicit grant on a resource or workspace (default deny, SEC-01 §5 "via grant"). */
        GRANT,
        /** Only meaningful as an API key scope limiting a service account (never in bundles or grants). */
        KEY_SCOPE
    }

    private static final Map<String, Permission> BY_VALUE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(Permission::value, Function.identity()));

    private final String value;
    private final Kind kind;

    Permission(String value, Kind kind) {
        this.value = value;
        this.kind = kind;
    }

    /**
     * Returns the stored textual form.
     *
     * @return {@code <resource>:<action>}
     */
    public String value() {
        return value;
    }

    /**
     * Returns how the permission can be held.
     *
     * @return the kind
     */
    public Kind kind() {
        return kind;
    }

    /**
     * Whether the permission acts on the data plane (invocations and writes). For those, the authorization engine
     * also checks kill switches and resource publication state (SEC-01 §7 steps 1–2).
     *
     * @return {@code true} for data-plane permissions
     */
    public boolean dataPlane() {
        return kind != Kind.ROLE;
    }

    /**
     * Whether a successful check must additionally be followed by a second, independent approval (four-eyes).
     *
     * @return {@code true} for {@link #CATALOG_DECLASSIFY}
     */
    public boolean requiresSecondApproval() {
        return this == CATALOG_DECLASSIFY;
    }

    /**
     * Resolves the textual form.
     *
     * @param value a stored permission value, case-insensitive
     * @return the permission, or empty if unknown
     */
    public static Optional<Permission> fromValue(String value) {
        return Optional.ofNullable(BY_VALUE.get(value.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * Resolves the textual form or fails.
     *
     * @param value a stored permission value
     * @return the permission
     * @throws IllegalArgumentException if the value is unknown
     */
    public static Permission of(String value) {
        return fromValue(value).orElseThrow(() -> new IllegalArgumentException("unknown permission: " + value));
    }

    @Override
    public String toString() {
        return value;
    }
}
