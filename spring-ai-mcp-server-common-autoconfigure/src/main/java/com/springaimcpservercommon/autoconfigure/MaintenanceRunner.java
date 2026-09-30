package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.persistence.config.NodeHeartbeat;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Background maintenance of one node (OQ-46, LLD-09 §4, LLD-15 §10). Every node runs it; work that must happen once
 * per cluster is guarded inside the steps (PostgreSQL advisory lock, {@code FOR UPDATE SKIP LOCKED}), so nodes need
 * no coordination service (ADR-0021).
 *
 * <p>Two daemon threads keep slow work away from liveness:
 * <ul>
 *   <li>{@code dai-node}: polls for a newer published generation and reports the node's heartbeat with the
 *       generation it serves (F-73);</li>
 *   <li>{@code dai-maintenance}: the sweeps (stale approvals, silent nodes, stuck applies) at a fixed delay and the
 *       partition maintenance on a cron (UTC).</li>
 * </ul>
 * A failing step is logged by exception class only (never message, so no row data can leak), counted, and retried
 * at its next tick; steps never stop each other. Nothing here is a {@code @Component}; the bean lives in
 * {@link DaiPersistenceAutoConfiguration}.
 */
@NullMarked
final class MaintenanceRunner implements AutoCloseable {

    /** Proposals failed per sweep at most; the rest is picked up at the next sweep. */
    static final int APPLY_BATCH = 100;

    private static final Duration EXPIRY_WARNING_REPEAT = Duration.ofHours(6);

    private static final Logger LOG = LoggerFactory.getLogger(MaintenanceRunner.class);

    /**
     * The store operations the runner drives, as functions so the runner has no store dependency of its own.
     *
     * @param refreshSnapshots     loads a newer published generation into the request-path caches
     * @param appliedGeneration    generation this node serves, 0 when none is loaded yet
     * @param heartbeat            writes the node heartbeat
     * @param pruneNodes           removes nodes silent for too long; returns the number removed
     * @param expireApprovals      marks approvals that were never published as stale; returns the number expired
     * @param failStuckApplies     marks proposals stuck in APPLYING as failed; returns the number failed
     * @param partitionMaintenance runs the advisory-locked partition maintenance and retention purge
     * @param expiringApiKeys      counts active API keys about to expire (an early warning, changes nothing)
     */
    record Steps(Runnable refreshSnapshots, LongSupplier appliedGeneration, Consumer<NodeHeartbeat> heartbeat,
                 IntSupplier pruneNodes, IntSupplier expireApprovals, IntSupplier failStuckApplies,
                 Runnable partitionMaintenance, IntSupplier expiringApiKeys) {}

    /**
     * Identity reported in every heartbeat.
     *
     * @param nodeId          stable id of this node
     * @param hostApplication host application name
     * @param hostVersion     host application version, if known
     * @param libraryVersion  version of this library
     */
    record Identity(String nodeId, String hostApplication, @Nullable String hostVersion, String libraryVersion) {}

    private final Steps steps;
    private final Identity identity;
    private final DaiProperties.Maintenance settings;
    private final CronExpression cron;
    private final Clock clock;
    private final Instant startedAt;
    private final ScheduledExecutorService nodeThread = Executors.newSingleThreadScheduledExecutor(
            r -> daemon(r, "dai-node"));
    private final ScheduledExecutorService maintenanceThread = Executors.newSingleThreadScheduledExecutor(
            r -> daemon(r, "dai-maintenance"));
    private volatile boolean closed;
    private int lastExpiringWarned;
    private Instant lastExpiringWarnedAt = Instant.MIN;

    MaintenanceRunner(Steps steps, Identity identity, DaiProperties.Maintenance settings, Clock clock) {
        this.steps = Objects.requireNonNull(steps, "steps");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cron = CronExpression.parse(settings.cron());
        this.startedAt = clock.instant();
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    /** Starts the schedules (bean init method). Initial delays are randomised so nodes do not run in lockstep. */
    void start() {
        long poll = settings.snapshotPollInterval().toMillis();
        long beat = settings.heartbeatInterval().toMillis();
        long sweep = settings.sweepInterval().toMillis();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // First heartbeat is prompt so a starting node shows up in the cluster view; the poll runs first so it
        // reports the generation it actually serves.
        nodeThread.scheduleWithFixedDelay(this::pollSnapshots, 0, poll, TimeUnit.MILLISECONDS);
        nodeThread.scheduleWithFixedDelay(this::heartbeat, 1, beat, TimeUnit.SECONDS);
        maintenanceThread.scheduleWithFixedDelay(this::sweep, random.nextLong(sweep / 2, sweep), sweep,
                TimeUnit.MILLISECONDS);
        scheduleCron();
    }

    private void scheduleCron() {
        if (closed) {
            return;
        }
        Duration delay = untilNextCron(clock.instant());
        try {
            maintenanceThread.schedule(() -> {
                partitionMaintenance();
                scheduleCron();
            }, delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // closed while rescheduling
        }
    }

    /** Time from {@code now} to the next cron fire time (UTC); package-private for tests. */
    Duration untilNextCron(Instant now) {
        ZonedDateTime from = now.atZone(ZoneOffset.UTC);
        ZonedDateTime next = cron.next(from);
        if (next == null) {
            // a cron that never fires again: check back in a day rather than spin
            return Duration.ofDays(1);
        }
        Duration delay = Duration.between(from, next);
        return delay.isNegative() ? Duration.ZERO : delay;
    }

    /** Loads a newer generation into the caches; package-private for tests. */
    void pollSnapshots() {
        guarded("snapshot poll", "dynamic.ai.agent.maintenance.snapshot.poll", steps.refreshSnapshots()::run);
    }

    /** Reports this node; package-private for tests. */
    void heartbeat() {
        guarded("heartbeat", "dynamic.ai.agent.maintenance.heartbeat", () -> {
            long applied = steps.appliedGeneration().getAsLong();
            steps.heartbeat().accept(new NodeHeartbeat(identity.nodeId(), applied > 0 ? applied : null,
                    identity.hostApplication(), identity.hostVersion(), identity.libraryVersion(), startedAt));
        });
    }

    /** Runs the sweeps independently; package-private for tests. */
    void sweep() {
        guarded("node pruning", "dynamic.ai.agent.maintenance.nodes.pruned", () -> {
            int n = steps.pruneNodes().getAsInt();
            if (n > 0) {
                LOG.info("Pruned {} silent node(s) from the cluster view", n);
            }
        });
        guarded("approval expiry", "dynamic.ai.agent.maintenance.approvals.expired", () -> {
            int n = steps.expireApprovals().getAsInt();
            if (n > 0) {
                LOG.info("Expired {} unpublished approval(s)", n);
            }
        });
        guarded("api key expiry warning", "dynamic.ai.agent.maintenance.apikeys.expiring", this::warnExpiringKeys);
        guarded("apply reconciliation", "dynamic.ai.agent.maintenance.applies.failed", () -> {
            int n = steps.failStuckApplies().getAsInt();
            if (n > 0) {
                LOG.warn("Marked {} proposal(s) stuck in APPLYING as failed; verify the host state", n);
            }
        });
    }

    /** Warns when active API keys are about to expire; repeats only when the count changes or every 6 hours. */
    private void warnExpiringKeys() {
        int n = steps.expiringApiKeys().getAsInt();
        Instant now = clock.instant();
        if (n > 0 && (n != lastExpiringWarned || now.isAfter(lastExpiringWarnedAt.plus(EXPIRY_WARNING_REPEAT)))) {
            LOG.warn("{} API key(s) expire soon; services using them will be refused once they do. "
                    + "Create replacement keys and revoke the old ones", n);
            lastExpiringWarned = n;
            lastExpiringWarnedAt = now;
        } else if (n == 0) {
            lastExpiringWarned = 0;
        }
    }

    /** Runs the partition maintenance; package-private for tests. */
    void partitionMaintenance() {
        guarded("partition maintenance", "dynamic.ai.agent.maintenance.partition.runs",
                steps.partitionMaintenance()::run);
    }

    private static void guarded(String what, String meter, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            LOG.warn("Maintenance step '{}' failed ({}); retrying at its next tick", what,
                    e.getClass().getSimpleName());
            SafeMetrics.count(meter + ".failures");
        }
    }

    @Override
    public void close() {
        closed = true;
        nodeThread.shutdownNow();
        maintenanceThread.shutdownNow();
    }
}
