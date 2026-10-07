package com.springaimcpservercommon.loadtest.model;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Who may call an endpoint, as far as the project's Spring Security configuration says: from method annotations
 * ({@code @PreAuthorize}, {@code @Secured}, {@code @RolesAllowed}) or from the filter chain's request matchers.
 *
 * @param kind  how the endpoint is protected
 * @param roles for {@link Kind#ROLES}: the roles or authorities of which the caller needs one, without the
 *              {@code ROLE_} prefix, in declaration order
 */
public record Access(Kind kind, List<String> roles) {

    /** How an endpoint is protected. */
    public enum Kind {
        /** No authentication required ({@code permitAll}). */
        PUBLIC,
        /** Any authenticated caller. */
        AUTHENTICATED,
        /** A caller with one of the roles. */
        ROLES,
        /** Nobody ({@code denyAll}): not worth load testing. */
        DENIED
    }

    /** Compact constructor: defensive copy. */
    public Access {
        roles = List.copyOf(roles);
    }

    /**
     * @return access without authentication
     */
    public static Access open() {
        return new Access(Kind.PUBLIC, List.of());
    }

    /**
     * @return access for any authenticated caller
     */
    public static Access authenticated() {
        return new Access(Kind.AUTHENTICATED, List.of());
    }

    /**
     * @param roles roles of which one is needed ({@code ROLE_} prefix is dropped)
     * @return role-restricted access
     */
    public static Access roles(List<String> roles) {
        return new Access(Kind.ROLES, roles.stream().map(r -> r.startsWith("ROLE_") ? r.substring(5) : r)
                .distinct().toList());
    }

    /**
     * @return access nobody has
     */
    public static Access denied() {
        return new Access(Kind.DENIED, List.of());
    }

    /**
     * The identity a load test should use for the endpoint.
     *
     * @return the first role for {@link Kind#ROLES}, else {@code null} (the default identity, or none for public)
     */
    public @Nullable String role() {
        return kind == Kind.ROLES && !roles.isEmpty() ? roles.getFirst() : null;
    }
}
