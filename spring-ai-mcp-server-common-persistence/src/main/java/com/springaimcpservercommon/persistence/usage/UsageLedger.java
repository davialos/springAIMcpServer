package com.springaimcpservercommon.persistence.usage;

import com.springaimcpservercommon.persistence.unit.DaiStore;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.hibernate.query.NativeQuery;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Hourly usage ledger (LLD-10 §5–6). The metering writer batches deltas asynchronously (bounded queue, never on the
 * request path) and calls {@link #recordAll}; each bucket is updated with one atomic native
 * {@code INSERT … ON CONFLICT ON CONSTRAINT uq_usage_hourly DO UPDATE SET calls = calls + EXCLUDED.calls …}, so
 * concurrent writers on several nodes accumulate correctly without read-modify-write races.
 *
 * <p>The transaction sets {@code TimeZone} to UTC locally: {@code ck_usage_hourly_bucket} compares
 * {@code bucket_start} with {@code date_trunc('hour', bucket_start)}, which truncates in the session time zone, so a
 * session in a zone with a non-whole-hour offset (e.g. Asia/Kolkata, +05:30) would reject UTC hour buckets.
 */
public final class UsageLedger {

    /** Maximum number of deltas accepted by one {@link #recordAll} call (one transaction). */
    public static final int MAX_BATCH = 1_000;

    private final DaiStore store;
    private final Clock clock;
    private final String upsertSql;

    /**
     * Creates the ledger with the system UTC clock.
     *
     * @param store the persistence unit
     */
    public UsageLedger(DaiStore store) {
        this(store, Clock.systemUTC());
    }

    /**
     * Creates the ledger.
     *
     * @param store the persistence unit
     * @param clock time source (for budget windows)
     */
    public UsageLedger(DaiStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.upsertSql = """
                INSERT INTO %s.dai_usage_hourly AS u (bucket_start, workspace_id, agent_resource_id, principal_id,
                                                      provider, model, currency, calls, input_tokens, output_tokens,
                                                      cached_input_tokens, cost_micros)
                VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)
                ON CONFLICT ON CONSTRAINT uq_usage_hourly DO UPDATE
                   SET calls               = u.calls + EXCLUDED.calls,
                       input_tokens        = u.input_tokens + EXCLUDED.input_tokens,
                       output_tokens       = u.output_tokens + EXCLUDED.output_tokens,
                       cached_input_tokens = u.cached_input_tokens + EXCLUDED.cached_input_tokens,
                       cost_micros         = u.cost_micros + EXCLUDED.cost_micros
                """.formatted(store.schema());
    }

    /**
     * Adds one delta.
     *
     * @param delta usage to add
     */
    public void record(UsageDelta delta) {
        recordAll(List.of(Objects.requireNonNull(delta, "delta")));
    }

    /**
     * Adds a batch of deltas in one transaction; deltas of the same bucket are summed first.
     *
     * @param deltas at most {@link #MAX_BATCH} deltas
     */
    public void recordAll(Collection<UsageDelta> deltas) {
        Objects.requireNonNull(deltas, "deltas");
        if (deltas.isEmpty()) {
            return;
        }
        if (deltas.size() > MAX_BATCH) {
            throw new IllegalArgumentException("at most " + MAX_BATCH + " deltas per batch");
        }
        Map<UsageDelta.BucketKey, UsageDelta> merged = new LinkedHashMap<>();
        for (UsageDelta delta : deltas) {
            merged.merge(delta.key(), delta, UsageDelta::plus);
        }
        store.transactions().executeWithoutResult(status -> {
            EntityManager em = store.entityManager();
            em.createNativeQuery("SELECT set_config('TimeZone', 'UTC', true)").getSingleResult();
            for (UsageDelta d : merged.values()) {
                NativeQuery<?> upsert = em.createNativeQuery(upsertSql).unwrap(NativeQuery.class);
                upsert.setParameter(1, d.bucketStart(), Instant.class);
                upsert.setParameter(2, d.workspaceId(), UUID.class);
                upsert.setParameter(3, d.agentResourceId(), UUID.class);
                upsert.setParameter(4, d.principalId(), UUID.class);
                upsert.setParameter(5, d.provider(), String.class);
                upsert.setParameter(6, d.model(), String.class);
                upsert.setParameter(7, d.currency(), String.class);
                upsert.setParameter(8, d.calls(), Integer.class);
                upsert.setParameter(9, d.inputTokens(), Long.class);
                upsert.setParameter(10, d.outputTokens(), Long.class);
                upsert.setParameter(11, d.cachedInputTokens(), Long.class);
                upsert.setParameter(12, d.costMicros(), Long.class);
                upsert.executeUpdate();
            }
        });
    }

    /**
     * Summed usage of a target over a window of hourly buckets.
     *
     * @param target whose usage (GLOBAL = all)
     * @param window [from, to) — buckets whose start lies in it
     * @return totals
     */
    public UsageTotals totals(BudgetTarget target, UsageWindow window) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(window, "window");
        StringBuilder jpql = new StringBuilder("select u.currency, sum(u.calls), sum(u.inputTokens), sum(u.outputTokens),"
                + " sum(u.cachedInputTokens), sum(u.costMicros) from UsageHourly u"
                + " where u.bucketStart >= :from and u.bucketStart < :to");
        Map<String, Object> params = new HashMap<>();
        switch (target) {
            case BudgetTarget.Global ignored -> {
                // no filter
            }
            case BudgetTarget.Workspace w -> {
                jpql.append(" and u.workspaceId = :ws");
                params.put("ws", w.workspaceId());
            }
            case BudgetTarget.Agent a -> {
                jpql.append(" and u.workspaceId = :ws and u.agentResourceId = :agent");
                params.put("ws", a.workspaceId());
                params.put("agent", a.agentResourceId());
            }
            case BudgetTarget.Principal p -> {
                jpql.append(" and u.principalId = :principal");
                params.put("principal", p.principalId());
                if (p.workspaceId() != null) {
                    jpql.append(" and u.workspaceId = :ws");
                    params.put("ws", p.workspaceId());
                }
            }
        }
        jpql.append(" group by u.currency");
        List<Object[]> rows = store.readOnlyTransactions().execute(status -> {
            TypedQuery<Object[]> query = store.entityManager().createQuery(jpql.toString(), Object[].class)
                    .setParameter("from", window.from())
                    .setParameter("to", window.to());
            params.forEach(query::setParameter);
            return query.getResultList();
        });
        long calls = 0;
        long input = 0;
        long output = 0;
        long cached = 0;
        Map<String, Long> cost = new HashMap<>();
        for (Object[] row : rows) {
            calls += asLong(row[1]);
            input += asLong(row[2]);
            output += asLong(row[3]);
            cached += asLong(row[4]);
            if (row[0] != null) {
                cost.merge(((String) row[0]).trim(), asLong(row[5]), Long::sum);
            }
        }
        return new UsageTotals(calls, input, output, cached, cost);
    }

    /**
     * Usage of a budget's target in its current period (UTC day or month containing now).
     *
     * @param budget the budget
     * @return totals of the current period
     */
    public UsageTotals currentPeriodTotals(BudgetView budget) {
        Objects.requireNonNull(budget, "budget");
        return totals(budget.target(), budget.period().windowContaining(clock.instant()));
    }

    private static long asLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }
}
