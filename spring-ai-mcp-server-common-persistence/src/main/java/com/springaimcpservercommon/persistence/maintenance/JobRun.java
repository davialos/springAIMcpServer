package com.springaimcpservercommon.persistence.maintenance;

import com.springaimcpservercommon.core.id.Ids;
import com.springaimcpservercommon.persistence.support.Checks;
import com.springaimcpservercommon.persistence.support.UtcTimes;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Outcome record of a maintenance job run ({@code dai_job_run}); written once when the run finishes. Observability
 * only — coordination between nodes uses advisory locks.
 */
@Entity
@Immutable
@Table(name = "dai_job_run")
public class JobRun {

    /** Pattern of job names ({@code ck_job_run_name}). */
    public static final Pattern JOB_NAME = Pattern.compile("^[a-z][a-z0-9-]{2,63}$");

    /** Longest message stored. */
    public static final int MAX_MESSAGE_LENGTH = 4000;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "job_name", nullable = false, updatable = false)
    private String jobName;

    @Column(name = "node_id", nullable = false, updatable = false)
    private String nodeId;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at", updatable = false)
    private @Nullable Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", updatable = false)
    private @Nullable JobOutcome outcome;

    @Column(name = "items", updatable = false)
    private @Nullable Integer items;

    @Column(name = "message", updatable = false)
    private @Nullable String message;

    /** For JPA only. */
    protected JobRun() {
    }

    /**
     * A finished run ({@code ck_job_run_finish}: finish time and outcome together).
     *
     * @param jobName    job name
     * @param nodeId     node that ran it
     * @param startedAt  start
     * @param finishedAt end
     * @param outcome    outcome
     * @param items      items processed, if meaningful
     * @param message    summary without row data (truncated to {@value #MAX_MESSAGE_LENGTH} characters), if any
     * @return a new, unsaved row
     */
    public static JobRun finished(String jobName, String nodeId, Instant startedAt, Instant finishedAt,
                                  JobOutcome outcome, @Nullable Integer items, @Nullable String message) {
        JobRun r = new JobRun();
        r.id = Ids.newId();
        r.jobName = Checks.matches(jobName, JOB_NAME, "jobName");
        r.nodeId = Checks.text(nodeId, "nodeId", 256);
        r.startedAt = UtcTimes.micros(startedAt);
        r.finishedAt = UtcTimes.micros(finishedAt);
        Checks.notBefore(r.startedAt, r.finishedAt, "job run");
        r.outcome = Checks.required(outcome, "outcome");
        r.items = items;
        r.message = message == null || message.isBlank() ? null
                : message.length() > MAX_MESSAGE_LENGTH ? message.substring(0, MAX_MESSAGE_LENGTH) : message;
        return r;
    }

    /** @return run id */
    public UUID getId() {
        return id;
    }

    /** @return job name */
    public String getJobName() {
        return jobName;
    }

    /** @return node id */
    public String getNodeId() {
        return nodeId;
    }

    /** @return start */
    public Instant getStartedAt() {
        return startedAt;
    }

    /** @return end, if finished */
    public @Nullable Instant getFinishedAt() {
        return finishedAt;
    }

    /** @return outcome, if finished */
    public @Nullable JobOutcome getOutcome() {
        return outcome;
    }

    /** @return items processed, if recorded */
    public @Nullable Integer getItems() {
        return items;
    }

    /** @return summary message, if any */
    public @Nullable String getMessage() {
        return message;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof JobRun other && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
