package com.springaimcpservercommon.webmvc.endpoint;

import com.springaimcpservercommon.ai.runtime.StreamEvent;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Port: stores and replays the ordered {@link StreamEvent} sequence of a streaming agent turn
 * to support SSE reconnection (LLD-13 §5).
 *
 * <p>The default implementation ({@link InMemoryTurnEventBuffer}) is registered by the
 * {@code autoconfigure} module. A PostgreSQL-backed alternative can be provided to support
 * event replay across replicas (ADR-0021).
 *
 * <p>Implementations must be thread-safe.
 */
@NullMarked
public interface TurnEventBuffer {

    /**
     * Appends an event to the buffer for the given turn.
     *
     * <p>When the buffer is at capacity for this turn, the oldest event is discarded so that
     * later events (closer to the current end) are preserved.
     *
     * <p>The first append of a turn binds the turn to its owner; later appends for the same turn must use
     * the same owner.
     *
     * @param turnId  the turn identifier
     * @param ownerId principal that started the turn; only this principal may read the events back
     * @param seq     monotonically increasing sequence number (matches the SSE {@code id} suffix)
     * @param event   the event to buffer
     */
    void append(UUID turnId, UUID ownerId, int seq, StreamEvent event);

    /**
     * Returns all buffered events for the given turn with sequence number {@code > afterSeq}.
     *
     * @param turnId   the turn identifier
     * @param ownerId  principal asking; must be the principal that started the turn
     * @param afterSeq exclusive lower bound on sequence number; {@code -1} to retrieve all events
     * @return immutable list of matching events (empty if none match), or {@code null} when the
     *         turn is unknown, has expired beyond the replay window, or belongs to another principal
     *         (callers must not be able to tell these cases apart)
     */
    @Nullable List<BufferedEvent> since(UUID turnId, UUID ownerId, int afterSeq);

    /**
     * Marks the turn as complete. The buffer retains events for replay until the configured TTL
     * expires, then frees the resources.
     *
     * @param turnId the turn to mark complete
     */
    void complete(UUID turnId);

    /**
     * A single buffered event with its sequence number.
     *
     * @param seq   the sequence number assigned when the event was emitted
     * @param event the typed stream event
     */
    record BufferedEvent(int seq, StreamEvent event) {}
}
