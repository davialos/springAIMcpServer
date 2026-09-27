package com.springaimcpservercommon.persistence.config;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Cluster convergence view for the admin dashboard (F-73, LLD-09 §4).
 *
 * @param latestGeneration latest published generation, {@code null} before the first publish
 * @param nodes            known nodes ordered by node id
 */
public record ClusterStatus(@Nullable Long latestGeneration, List<Node> nodes) {

    /** Copies the node list. */
    public ClusterStatus {
        nodes = List.copyOf(nodes);
    }

    /**
     * Whether every alive node serves the latest generation.
     *
     * @return {@code true} when converged
     */
    public boolean converged() {
        return nodes.stream().filter(Node::alive).allMatch(Node::current);
    }

    /**
     * One node.
     *
     * @param nodeId            node id
     * @param appliedGeneration generation it serves
     * @param hostApplication   host application
     * @param hostVersion       host version
     * @param libraryVersion    library version
     * @param startedAt         start time
     * @param heartbeatAt       last heartbeat
     * @param alive             heartbeat within the requested window
     * @param current           serves the latest generation
     */
    public record Node(
            String nodeId,
            @Nullable Long appliedGeneration,
            String hostApplication,
            @Nullable String hostVersion,
            String libraryVersion,
            Instant startedAt,
            Instant heartbeatAt,
            boolean alive,
            boolean current) {

        /** Validates components. */
        public Node {
            Objects.requireNonNull(nodeId, "nodeId");
        }
    }
}
