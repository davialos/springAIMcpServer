package com.springaimcpservercommon.autoconfigure;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The {@code dai.snapshot.apply} span / {@code dynamic.ai.agent.snapshot.apply} timer (LLD-10 §3): one per published
 * generation a snapshot cache loads, never per poll, so it shows how long a node takes to pick up a publish. Without a
 * registry (the host has none) it costs nothing.
 */
@NullMarked
final class SnapshotObservations {

    private SnapshotObservations() {
    }

    /** An open span; close it exactly once. */
    static final class Span implements AutoCloseable {
        private final @Nullable Observation observation;
        private String outcome = "applied";

        private Span(@Nullable Observation observation) {
            this.observation = observation;
        }

        /** Marks the load as ended without applying anything (for example the snapshot was not found). */
        void outcome(String outcome) {
            this.outcome = outcome;
        }

        /** Marks the load as failed; the exception is rethrown by the caller. */
        void fail(RuntimeException e) {
            outcome = "failed";
            if (observation != null) {
                observation.error(e);
            }
        }

        @Override
        public void close() {
            if (observation != null) {
                observation.lowCardinalityKeyValue("dai.snapshot.outcome", outcome);
                observation.stop();
            }
        }
    }

    /**
     * Opens the span.
     *
     * @param registry   the host's registry, or {@code null}
     * @param cache      which cache loads ({@code agents}, {@code queries}, {@code tool-bindings})
     * @param generation the generation being applied
     * @return the open span
     */
    static Span open(@Nullable ObservationRegistry registry, String cache, long generation) {
        if (registry == null || registry.isNoop()) {
            return new Span(null);
        }
        return new Span(Observation.createNotStarted("dynamic.ai.agent.snapshot.apply", registry)
                .contextualName("dai.snapshot.apply")
                .lowCardinalityKeyValue("dai.snapshot.cache", cache)
                .highCardinalityKeyValue("dai.snapshot.generation", String.valueOf(generation))
                .start());
    }
}
