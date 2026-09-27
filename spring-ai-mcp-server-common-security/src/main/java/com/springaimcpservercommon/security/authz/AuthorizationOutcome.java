package com.springaimcpservercommon.security.authz;

import com.springaimcpservercommon.core.principal.FrameworkRole;
import com.springaimcpservercommon.security.permission.Permission;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Result of {@link AuthorizationEngine#decide}: {@link Permit} or {@link Deny} (SEC-01 §7).
 */
public sealed interface AuthorizationOutcome permits AuthorizationOutcome.Permit, AuthorizationOutcome.Deny {

    /**
     * Requested permission.
     *
     * @return permission
     */
    Permission permission();

    /**
     * Whether access is granted.
     *
     * @return {@code true} for {@link Permit}
     */
    default boolean granted() {
        return this instanceof Permit;
    }

    /** How a permit was obtained. */
    enum Via {
        /** Through a framework role bundle. */
        ROLE,
        /** Through a {@code dai_grant} row. */
        GRANT
    }

    /**
     * Access granted.
     *
     * @param permission      permission
     * @param via             role bundle or grant
     * @param grantId         matching grant id (when {@code via == GRANT})
     * @param role            role whose bundle granted it (when {@code via == ROLE})
     * @param maskingRequired whether data above the caller's clearance must be masked (classification policy MASK)
     */
    record Permit(Permission permission, Via via, @Nullable UUID grantId, @Nullable FrameworkRole role,
                  boolean maskingRequired) implements AuthorizationOutcome {
        /**
         * Validates consistency.
         */
        public Permit {
            Objects.requireNonNull(permission, "permission");
            Objects.requireNonNull(via, "via");
            if ((via == Via.GRANT) != (grantId != null) || (via == Via.ROLE) != (role != null)) {
                throw new IllegalArgumentException("grantId/role inconsistent with via");
            }
        }

        /**
         * Returns a copy that requires masking.
         *
         * @return masked permit
         */
        public Permit withMasking() {
            return new Permit(permission, via, grantId, role, true);
        }
    }

    /**
     * Access denied.
     *
     * @param permission permission
     * @param reason     reason code
     * @param reference  optional non-sensitive reference (kill switch id or grant id), for audit only
     */
    record Deny(Permission permission, DenyReason reason, @Nullable UUID reference) implements AuthorizationOutcome {
        /**
         * Validates components.
         */
        public Deny {
            Objects.requireNonNull(permission, "permission");
            Objects.requireNonNull(reason, "reason");
        }

        /**
         * A denial without reference.
         *
         * @param permission permission
         * @param reason     reason
         * @return the denial
         */
        public static Deny of(Permission permission, DenyReason reason) {
            return new Deny(permission, reason, null);
        }
    }
}
