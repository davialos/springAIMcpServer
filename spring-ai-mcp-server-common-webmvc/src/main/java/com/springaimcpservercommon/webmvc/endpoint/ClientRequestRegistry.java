package com.springaimcpservercommon.webmvc.endpoint;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Remembers the client-supplied {@code clientRequestId} of recent chat turns per (principal, agent) so a retry
 * of the same request (double click, network retry) does not start a second turn and pay for it twice.
 *
 * <p>State is local to the node and bounded (entries expire after the TTL; the map is cleared when it reaches
 * its cap), consistent with the replay buffer (ADR-0021): behind a round-robin balancer a retry that lands on
 * another node is not detected, which costs one extra turn but never blocks a user. A registration is
 * released when the turn fails or is cancelled, so the client can retry it.
 */
@NullMarked
final class ClientRequestRegistry {

    static final Duration DEFAULT_TTL = Duration.ofMinutes(5);
    static final int MAX_ENTRIES = 10_000;

    private record Key(UUID principalId, UUID agentId, String clientRequestId) {}

    private record Entry(UUID turnId, Instant expiresAt) {}

    private final ConcurrentMap<Key, Entry> entries = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;

    ClientRequestRegistry() {
        this(DEFAULT_TTL, Clock.systemUTC());
    }

    ClientRequestRegistry(Duration ttl, Clock clock) {
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Registers a request unless the same one is already known.
     *
     * @param principalId     caller
     * @param agentId         agent
     * @param clientRequestId client-supplied id
     * @param turnId          turn started for this request
     * @return {@code null} when this call registered the request, otherwise the turn id of the earlier one
     */
    @Nullable UUID register(UUID principalId, UUID agentId, String clientRequestId, UUID turnId) {
        Instant now = clock.instant();
        if (entries.size() >= MAX_ENTRIES) {
            entries.clear();
        }
        Key key = new Key(principalId, agentId, clientRequestId);
        Entry fresh = new Entry(turnId, now.plus(ttl));
        UUID[] existing = new UUID[1];
        entries.compute(key, (k, old) -> {
            if (old != null && old.expiresAt().isAfter(now)) {
                existing[0] = old.turnId();
                return old;
            }
            return fresh;
        });
        return existing[0];
    }

    /**
     * Forgets a request so the client may retry it (called when the turn failed or was cancelled).
     *
     * @param principalId     caller
     * @param agentId         agent
     * @param clientRequestId client-supplied id
     */
    void release(UUID principalId, UUID agentId, String clientRequestId) {
        entries.remove(new Key(principalId, agentId, clientRequestId));
    }
}
