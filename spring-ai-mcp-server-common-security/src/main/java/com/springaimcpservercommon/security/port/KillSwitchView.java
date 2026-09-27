package com.springaimcpservercommon.security.port;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Port to the active {@code dai_kill_switch} rows (F-73). Implementations serve it from the in-memory set refreshed
 * by the kill-switch poller (≈ 2 s), never with a database round trip per call.
 */
public interface KillSwitchView {

    /**
     * Returns an active switch that covers the invocation: GLOBAL, the workspace, the resource or the tool name.
     * Active = not cleared and not expired at {@code at}.
     *
     * @param workspaceId workspace of the invocation, if any
     * @param resourceId  invoked resource, if any
     * @param toolName    invoked tool name, if any
     * @param at          evaluation time
     * @return the first covering switch
     */
    Optional<ActiveKillSwitch> findActive(@Nullable UUID workspaceId, @Nullable UUID resourceId, @Nullable String toolName,
                                          Instant at);

    /**
     * An active kill switch.
     *
     * @param id    switch id
     * @param scope switch scope
     */
    record ActiveKillSwitch(UUID id, Scope scope) {
        /**
         * Validates components.
         */
        public ActiveKillSwitch {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(scope, "scope");
        }
    }

    /** Scope of a kill switch ({@code dai_kill_switch.scope}). */
    enum Scope {
        /** Everything. */
        GLOBAL,
        /** One workspace. */
        WORKSPACE,
        /** One resource. */
        RESOURCE,
        /** One tool name. */
        TOOL
    }
}
