package com.springaimcpservercommon.persistence.config;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable view of a kill switch.
 *
 * @param id        switch id
 * @param target    what it disables
 * @param reason    reason
 * @param setBy     principal that set it
 * @param setAt     set time
 * @param expiresAt automatic expiry, if any
 * @param clearedBy principal that cleared it, if cleared
 * @param clearedAt clear time, if cleared
 */
public record KillSwitchView(
        UUID id,
        KillSwitchTarget target,
        String reason,
        UUID setBy,
        Instant setAt,
        @Nullable Instant expiresAt,
        @Nullable UUID clearedBy,
        @Nullable Instant clearedAt) {

    /**
     * Whether the switch is in force at the given time.
     *
     * @param now evaluation time
     * @return {@code true} if not cleared and not expired
     */
    public boolean activeAt(Instant now) {
        return clearedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }
}
