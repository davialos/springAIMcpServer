package com.springaimcpservercommon.persistence.maintenance;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Result of one {@link PartitionMaintenance#run()}.
 *
 * @param jobRunId                  id of the recorded {@code dai_job_run} row
 * @param outcome                   overall outcome
 * @param startedAt                 start
 * @param finishedAt                end
 * @param partitionsCreated         monthly partitions created
 * @param partitionsDropped         monthly partitions dropped by retention
 * @param nonEmptyDefaultPartitions DEFAULT partitions that hold rows (should be empty; alert)
 * @param proposalsExpired          pending proposals expired
 * @param proposalsPurged           terminal proposals deleted after retention
 * @param conversationsPurged       conversations deleted after retention
 * @param failures                  failed steps with the exception type (no row data)
 */
public record MaintenanceResult(
        UUID jobRunId,
        JobOutcome outcome,
        Instant startedAt,
        Instant finishedAt,
        int partitionsCreated,
        int partitionsDropped,
        List<String> nonEmptyDefaultPartitions,
        int proposalsExpired,
        int proposalsPurged,
        int conversationsPurged,
        List<String> failures) {

    /**
     * Copies the lists.
     */
    public MaintenanceResult {
        nonEmptyDefaultPartitions = List.copyOf(nonEmptyDefaultPartitions);
        failures = List.copyOf(failures);
    }

    /**
     * Total items processed (recorded as {@code dai_job_run.items}).
     *
     * @return sum of all counters
     */
    public int items() {
        return partitionsCreated + partitionsDropped + proposalsExpired + proposalsPurged + conversationsPurged;
    }
}
