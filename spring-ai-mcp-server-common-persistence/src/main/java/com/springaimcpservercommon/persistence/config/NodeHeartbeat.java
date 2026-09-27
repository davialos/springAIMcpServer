package com.springaimcpservercommon.persistence.config;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * What a node reports on each heartbeat (F-73).
 *
 * @param nodeId            stable id of this node (e.g. host name + instance id)
 * @param appliedGeneration generation the node currently serves, {@code null} before the first apply
 * @param hostApplication   host application name
 * @param hostVersion       host application version, if known
 * @param libraryVersion    version of this library
 * @param startedAt         node start time
 */
public record NodeHeartbeat(
        String nodeId,
        @Nullable Long appliedGeneration,
        String hostApplication,
        @Nullable String hostVersion,
        String libraryVersion,
        Instant startedAt) {

    /** Validates components. */
    public NodeHeartbeat {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(hostApplication, "hostApplication");
        Objects.requireNonNull(libraryVersion, "libraryVersion");
        Objects.requireNonNull(startedAt, "startedAt");
        if (nodeId.isBlank() || nodeId.length() > 255) {
            throw new IllegalArgumentException("nodeId must have 1..255 characters");
        }
    }
}
