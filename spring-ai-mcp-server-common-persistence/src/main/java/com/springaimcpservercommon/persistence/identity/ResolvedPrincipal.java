package com.springaimcpservercommon.persistence.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * Result of resolving an external subject to its {@code dai_principal} row.
 *
 * @param id     principal id
 * @param status current status; callers must refuse {@link PrincipalStatus#DISABLED} principals
 */
public record ResolvedPrincipal(UUID id, PrincipalStatus status) {

    /** Validates components. */
    public ResolvedPrincipal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
    }

    /**
     * Whether the principal may act.
     *
     * @return {@code true} when active
     */
    public boolean active() {
        return status == PrincipalStatus.ACTIVE;
    }
}
