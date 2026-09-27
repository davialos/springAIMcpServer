package com.springaimcpservercommon.persistence.config;

import java.util.Objects;
import java.util.UUID;

/**
 * What a grant applies to within its workspace (SEC-01 §7, {@code ck_grant_target}: at most one of resource and
 * pattern).
 */
public sealed interface GrantTarget {

    /** Every resource of the workspace. */
    record WorkspaceWide() implements GrantTarget {
    }

    /**
     * One resource.
     *
     * @param resourceId resource
     */
    record OnResource(UUID resourceId) implements GrantTarget {
        /** Validates components. */
        public OnResource {
            Objects.requireNonNull(resourceId, "resourceId");
        }
    }

    /**
     * Resources whose {@code <kind>/<slug>} matches a pattern; matching is done by the security module's
     * authorization engine, the store only persists it.
     *
     * @param pattern resource pattern, e.g. {@code query/orders-*}
     */
    record OnPattern(String pattern) implements GrantTarget {
        /** Validates components. */
        public OnPattern {
            Objects.requireNonNull(pattern, "pattern");
            if (pattern.isBlank() || pattern.length() > 256) {
                throw new IllegalArgumentException("resource pattern must have 1..256 characters");
            }
        }
    }
}
