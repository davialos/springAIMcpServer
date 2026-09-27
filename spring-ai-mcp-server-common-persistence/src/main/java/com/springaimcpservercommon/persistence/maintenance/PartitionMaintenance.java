package com.springaimcpservercommon.persistence.maintenance;

import com.springaimcpservercommon.persistence.proposal.ChangeProposalStore;
import com.springaimcpservercommon.persistence.support.Checks;
import com.springaimcpservercommon.persistence.support.StoreSupport;
import com.springaimcpservercommon.persistence.support.UtcTimes;
import com.springaimcpservercommon.persistence.telemetry.TelemetryStore;
import com.springaimcpservercommon.persistence.unit.DaiStore;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.regex.Pattern;

/**
 * Daily store maintenance (migration V6, OQ-31), safe to schedule on every node:
 * <ol>
 *   <li>takes the transaction-scoped advisory lock {@value #LOCK_KEY} ({@code pg_try_advisory_xact_lock},
 *       ASCII {@code "dai_pmnt"}); if another node holds it, the run is recorded as SKIPPED. The transaction-scoped
 *       variant is used instead of the session-level {@code pg_try_advisory_lock} so the lock can never leak onto a
 *       pooled connection: it is released when the holding transaction ends, even on failure;</li>
 *   <li>for every table in {@code dai_partitioned_table}: {@code dai_ensure_monthly_partitions(table, 1,
 *       months_ahead)}, then {@code dai_drop_monthly_partitions_before(table, first day of (current UTC month −
 *       retention))} with retention from the overrides map or the registry (evidence partitions under legal hold are
 *       kept by the function), then a WARN if the table's DEFAULT partition holds rows;</li>
 *   <li>expires due proposals, purges terminal proposals and conversations past retention;</li>
 *   <li>records one {@code dai_job_run} row and returns a {@link MaintenanceResult}.</li>
 * </ol>
 * Every step runs in its own transaction and a failing step does not stop the others (outcome PARTIAL). The lock
 * holder keeps one pooled connection for the duration of the run while each step uses a second one, so the pool needs
 * at least two connections. Partition creation uses the database clock ({@code now()} inside the function); retention
 * cut-offs and sweeps use the injected {@link Clock}. Do not call {@link #run()} inside a unit transaction.
 */
public final class PartitionMaintenance {

    /** Job name recorded in {@code dai_job_run}. */
    public static final String JOB_NAME = "partition-maintenance";

    /** Advisory lock key: the ASCII bytes of {@code "dai_pmnt"} as a big-endian 64-bit integer. */
    public static final long LOCK_KEY = 0x6461695F706D6E74L;

    /** Batch size of proposal and conversation sweeps. */
    public static final int SWEEP_BATCH = 500;

    /** Maximum sweep batches per run (bounds the run time; the rest is picked up next run). */
    public static final int MAX_SWEEP_BATCHES = 200;

    private static final Pattern TABLE = Pattern.compile("^dai_[a-z_]{3,56}$");
    private static final Logger log = LoggerFactory.getLogger(PartitionMaintenance.class);

    private final DaiStore store;
    private final StoreSupport db;
    private final ChangeProposalStore proposals;
    private final TelemetryStore telemetry;
    private final Clock clock;
    private final String nodeId;
    private final Map<String, Integer> retentionOverrides;

    /**
     * Creates the job.
     *
     * @param store              the persistence unit
     * @param proposals          proposal store (expiry and purge)
     * @param telemetry          telemetry store (conversation purge)
     * @param clock              clock for retention cut-offs
     * @param nodeId             id of this node, recorded in {@code dai_job_run}
     * @param retentionOverrides retention in months per partitioned table name (1–120), overriding the registry
     *                           ({@code dynamic.ai.agent.store.retention.*})
     */
    public PartitionMaintenance(DaiStore store, ChangeProposalStore proposals, TelemetryStore telemetry, Clock clock,
                                String nodeId, Map<String, Integer> retentionOverrides) {
        this.store = Objects.requireNonNull(store, "store");
        this.db = new StoreSupport(store);
        this.proposals = Objects.requireNonNull(proposals, "proposals");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nodeId = Checks.text(nodeId, "nodeId", 256);
        retentionOverrides.forEach((table, months) -> {
            Checks.matches(table, TABLE, "retention override table");
            if (months == null || months < 1 || months > 120) {
                throw new IllegalArgumentException("retention override for " + table + " must be 1-120 months");
            }
        });
        this.retentionOverrides = Map.copyOf(retentionOverrides);
    }

    /**
     * Runs the maintenance once (see the class description).
     *
     * @return what was done
     */
    public MaintenanceResult run() {
        Instant startedAt = UtcTimes.now(clock);
        Run run = new Run();
        try {
            store.newTransactions().executeWithoutResult(status -> {
                Object locked = store.entityManager().createNativeQuery("SELECT pg_try_advisory_xact_lock(?1)")
                        .setParameter(1, LOCK_KEY)
                        .getSingleResult();
                if (Boolean.TRUE.equals(locked)) {
                    maintain(run);
                } else {
                    run.skipped = true;
                }
            });
        } catch (RuntimeException e) {
            // lock transaction itself failed (connection, timeout); steps already done stay done
            run.failures.add("advisory lock transaction: " + e.getClass().getSimpleName());
            log.warn("dynamic_ai partition maintenance lock transaction failed", e);
        }
        return finish(startedAt, run);
    }

    private MaintenanceResult finish(Instant startedAt, Run run) {
        Instant finishedAt = UtcTimes.now(clock);
        JobOutcome outcome = run.skipped ? JobOutcome.SKIPPED
                : run.failures.isEmpty() ? JobOutcome.SUCCESS
                : run.succeededSteps == 0 ? JobOutcome.FAILED
                : JobOutcome.PARTIAL;
        String summary = run.summary();
        JobRun jobRun = JobRun.finished(JOB_NAME, nodeId, startedAt, finishedAt, outcome, run.items(), summary);
        db.writeNew(em -> {
            em.persist(jobRun);
            return jobRun;
        });
        if (outcome == JobOutcome.SKIPPED) {
            log.debug("dynamic_ai partition maintenance skipped: another node holds the lock");
        } else {
            log.info("dynamic_ai partition maintenance {}: {}", outcome, summary);
        }
        return new MaintenanceResult(jobRun.getId(), outcome, startedAt, finishedAt, run.created, run.dropped,
                run.nonEmptyDefaults, run.expired, run.proposalsPurged, run.conversationsPurged, run.failures);
    }

    private void maintain(Run run) {
        List<Object[]> registry = new ArrayList<>();
        step(run, "read dai_partitioned_table", () -> {
            registry.addAll(readRegistry());
            return 0;
        });
        for (Object[] row : registry) {
            String table = String.valueOf(row[0]);
            if (!TABLE.matcher(table).matches()) {
                run.failures.add("invalid table name in dai_partitioned_table");
                continue;
            }
            int registryRetention = ((Number) row[1]).intValue();
            int monthsAhead = ((Number) row[2]).intValue();
            int retention = retentionOverrides.getOrDefault(table, registryRetention);
            String qualified = db.qualified(table);
            run.created += step(run, "ensure partitions of " + table, () -> ensurePartitions(qualified, monthsAhead));
            run.dropped += step(run, "drop expired partitions of " + table,
                    () -> dropPartitionsBefore(qualified, retentionCutoff(retention)));
            step(run, "check default partition of " + table, () -> {
                if (defaultPartitionHasRows(db.qualified(table + "_pdefault"))) {
                    run.nonEmptyDefaults.add(table + "_pdefault");
                    log.warn("dynamic_ai: default partition {}_pdefault holds rows; a monthly partition is missing "
                            + "for their time range (create it and move the rows)", table);
                }
                return 0;
            });
        }
        run.expired += sweep(run, "expire due proposals", () -> proposals.expireDue(SWEEP_BATCH));
        run.proposalsPurged += sweep(run, "purge retained proposals", () -> proposals.purgeRetained(SWEEP_BATCH));
        run.conversationsPurged += sweep(run, "purge expired conversations",
                () -> telemetry.purgeExpiredConversations(SWEEP_BATCH));
    }

    /** Runs one step in its own transaction; failures are recorded and do not stop the run. */
    private int step(Run run, String name, IntSupplier work) {
        try {
            int count = Objects.requireNonNull(store.newTransactions().execute(status -> work.getAsInt()));
            run.succeededSteps++;
            return count;
        } catch (RuntimeException e) {
            run.failures.add(name + ": " + e.getClass().getSimpleName());
            log.warn("dynamic_ai partition maintenance step '{}' failed", name, e);
            return 0;
        }
    }

    /**
     * Repeats a batch operation, each batch in its own transaction (so locks are held briefly), until a batch
     * processes fewer than {@value #SWEEP_BATCH} rows or {@value #MAX_SWEEP_BATCHES} batches ran.
     */
    private int sweep(Run run, String name, IntSupplier batch) {
        int total = 0;
        for (int i = 0; i < MAX_SWEEP_BATCHES; i++) {
            int before = run.failures.size();
            int n = step(run, name, batch);
            total += n;
            if (n < SWEEP_BATCH || run.failures.size() > before) {
                break;
            }
            run.succeededSteps--; // count a sweep as one step, however many batches it took
        }
        return total;
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> readRegistry() {
        EntityManager em = store.entityManager();
        return em.createNativeQuery("SELECT table_name, retention_months, months_ahead FROM "
                        + db.qualified("dai_partitioned_table") + " ORDER BY table_name")
                .getResultList();
    }

    private int ensurePartitions(String qualifiedTable, int monthsAhead) {
        Object created = store.entityManager().createNativeQuery("SELECT "
                        + db.qualified("dai_ensure_monthly_partitions") + "(cast(?1 as regclass), 1, ?2)")
                .setParameter(1, qualifiedTable)
                .setParameter(2, monthsAhead)
                .getSingleResult();
        return ((Number) created).intValue();
    }

    private int dropPartitionsBefore(String qualifiedTable, Instant cutoff) {
        Object dropped = store.entityManager().createNativeQuery("SELECT "
                        + db.qualified("dai_drop_monthly_partitions_before") + "(cast(?1 as regclass), ?2)")
                .setParameter(1, qualifiedTable)
                .setParameter(2, cutoff)
                .getSingleResult();
        return ((Number) dropped).intValue();
    }

    private boolean defaultPartitionHasRows(String qualifiedDefault) {
        Object exists = store.entityManager().createNativeQuery("SELECT to_regclass(?1) IS NOT NULL")
                .setParameter(1, qualifiedDefault)
                .getSingleResult();
        if (!Boolean.TRUE.equals(exists)) {
            return false;
        }
        Object hasRows = store.entityManager().createNativeQuery("SELECT EXISTS (SELECT 1 FROM " + qualifiedDefault + ")")
                .getSingleResult();
        return Boolean.TRUE.equals(hasRows);
    }

    /**
     * First instant kept by retention: the start of the UTC month {@code months} before the current month.
     *
     * @param months retention in months
     * @return cut-off instant; partitions ending at or before it are dropped
     */
    Instant retentionCutoff(int months) {
        return YearMonth.now(clock.withZone(ZoneOffset.UTC)).minusMonths(months)
                .atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** Mutable accumulator of one run. */
    private static final class Run {
        boolean skipped;
        int succeededSteps;
        int created;
        int dropped;
        int expired;
        int proposalsPurged;
        int conversationsPurged;
        final List<String> nonEmptyDefaults = new ArrayList<>();
        final List<String> failures = new ArrayList<>();

        int items() {
            return created + dropped + expired + proposalsPurged + conversationsPurged;
        }

        String summary() {
            if (skipped) {
                return "skipped: advisory lock held by another node";
            }
            StringBuilder sb = new StringBuilder()
                    .append("created=").append(created)
                    .append(" dropped=").append(dropped)
                    .append(" expired=").append(expired)
                    .append(" proposalsPurged=").append(proposalsPurged)
                    .append(" conversationsPurged=").append(conversationsPurged);
            if (!nonEmptyDefaults.isEmpty()) {
                sb.append(" nonEmptyDefaults=").append(nonEmptyDefaults);
            }
            if (!failures.isEmpty()) {
                sb.append(" failures=").append(failures);
            }
            return sb.toString();
        }
    }
}
