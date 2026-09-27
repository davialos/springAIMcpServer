package com.springaimcpservercommon.core.principal;

/**
 * Framework roles (SEC-01 §4). Mapped from the host's IdP claims, authorities and groups — never assigned to
 * passwords we own. Values match {@code dai_role_mapping.framework_role} and {@code dai_workspace_member.role}.
 */
public enum FrameworkRole {
    /** Enables features, models, prices, global mappings and the global kill switch. */
    PLATFORM_ADMIN(true),
    /** Role mappings, service-account policy and audit configuration. */
    SECURITY_ADMIN(true),
    /** Read-only access to audit and access reviews (global or per workspace). */
    AUDITOR(false),
    /** Manages a workspace: members, grants, budgets, workspace kill switch. */
    WORKSPACE_OWNER(false),
    /** Creates and edits drafts, previews, playground. */
    AUTHOR(false),
    /** Approves or rejects revisions and sensitive write proposals. */
    APPROVER(false),
    /** Views usage and sets kill switches, no configuration edits. */
    OPERATOR(false),
    /** Invokes endpoints, agents and tools through grants. */
    CONSUMER(false);

    private final boolean globalOnly;

    FrameworkRole(boolean globalOnly) {
        this.globalOnly = globalOnly;
    }

    /**
     * Whether the role only exists globally (never scoped to a workspace).
     *
     * @return {@code true} for platform-wide roles
     */
    public boolean globalOnly() {
        return globalOnly;
    }
}
