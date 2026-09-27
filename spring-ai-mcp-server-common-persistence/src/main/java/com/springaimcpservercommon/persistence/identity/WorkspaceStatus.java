package com.springaimcpservercommon.persistence.identity;

/** Status of a workspace (matches {@code ck_workspace_status}). */
public enum WorkspaceStatus {
    /** In use. */
    ACTIVE,
    /** Retired; kept for audit, never deleted. Its memberships no longer grant roles. */
    ARCHIVED
}
