package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.advisor.InvocationGuardAdvisor;
import com.springaimcpservercommon.ai.agent.AgentDefinition;
import com.springaimcpservercommon.core.json.CanonicalJson;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.persistence.audit.AuditEventDraft;
import com.springaimcpservercommon.persistence.audit.AuditTrail;
import com.springaimcpservercommon.persistence.usage.BudgetLimits;
import com.springaimcpservercommon.persistence.usage.BudgetStore;
import com.springaimcpservercommon.persistence.usage.BudgetView;
import com.springaimcpservercommon.persistence.usage.UsageLedger;
import com.springaimcpservercommon.persistence.usage.UsageTotals;
import io.micrometer.core.instrument.Metrics;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Pre-turn budget enforcement on top of the usage ledger (F-70, LLD-10 §6).
 *
 * <p>For each turn it loads the enabled budgets that apply to (workspace, agent, principal), compares the
 * current-period usage with each limit and refuses the turn when a <em>hard</em> limit is reached. Soft
 * limits and non-hard limits only raise an alert (audit event and counter).
 *
 * <p>Accuracy: usage is recorded after each model call, so several concurrent turns can pass the check
 * together and overshoot a limit by the tokens in flight, plus up to {@code cacheTtl} of staleness. The
 * reservation model of LLD-10 §6 is not implemented (OQ-40). The decision is cached per
 * (workspace, agent, principal) for {@code cacheTtl} to keep the check off the hot path; the cache is local
 * to the node and stateless across restarts (ADR-0021).
 *
 * <p>If the store is unavailable the checker fails open by default ({@code failOpen}); a cost control must
 * not take agents down, and the failure is logged and counted.
 *
 * <p>Alerts are written to the audit trail as SYSTEM events {@code BUDGET_SOFT_LIMIT_REACHED} and
 * {@code BUDGET_EXCEEDED}, at most once per budget and period per node. Nothing about the prompt, the
 * answer or any row data is logged or stored.
 */
@NullMarked
final class LedgerBudgetChecker implements InvocationGuardAdvisor.BudgetChecker {

    private static final Logger LOG = LoggerFactory.getLogger(LedgerBudgetChecker.class);
    private static final int MAX_ENTRIES = 10_000;

    private record Key(UUID workspaceId, UUID agentId, UUID principalId) {}

    private record Decision(boolean allowed, Instant expiresAt) {}

    private final BudgetStore budgets;
    private final UsageLedger ledger;
    private final @Nullable AuditTrail auditTrail;
    private final Duration cacheTtl;
    private final boolean failOpen;
    private final Clock clock;
    private final ConcurrentMap<Key, Decision> cache = new ConcurrentHashMap<>();
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    LedgerBudgetChecker(BudgetStore budgets, UsageLedger ledger, @Nullable AuditTrail auditTrail, Duration cacheTtl,
                        boolean failOpen, Clock clock) {
        this.budgets = Objects.requireNonNull(budgets, "budgets");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.auditTrail = auditTrail;
        this.cacheTtl = Objects.requireNonNull(cacheTtl, "cacheTtl");
        if (cacheTtl.isNegative()) {
            throw new IllegalArgumentException("cacheTtl must not be negative");
        }
        this.failOpen = failOpen;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean hasRemainingBudget(AgentDefinition agent, DaiPrincipal principal) {
        Key key = new Key(agent.workspaceId(), agent.id(), principal.principalId());
        Instant now = clock.instant();
        Decision cached = cache.get(key);
        boolean allowed;
        if (cached != null && cached.expiresAt().isAfter(now)) {
            allowed = cached.allowed();
        } else {
            allowed = evaluate(key, now);
            if (!cacheTtl.isZero()) {
                if (cache.size() >= MAX_ENTRIES) {
                    cache.clear();
                }
                cache.put(key, new Decision(allowed, now.plus(cacheTtl)));
            }
        }
        if (!allowed) {
            count("dynamic.ai.agent.budget.blocked", "workspace", key.workspaceId().toString());
        }
        return allowed;
    }

    private boolean evaluate(Key key, Instant now) {
        try {
            List<BudgetView> applicable = budgets.applicable(key.workspaceId(), key.agentId(), key.principalId());
            boolean allowed = true;
            for (BudgetView budget : applicable) {
                UsageTotals totals = ledger.currentPeriodTotals(budget);
                BudgetLimits limits = budget.limits();
                double percent = BudgetMath.percentUsed(limits, totals);
                if (percent >= 100.0) {
                    report("BUDGET_EXCEEDED", budget, now);
                    if (limits.hardLimit()) {
                        allowed = false;
                    }
                } else if (percent >= limits.softLimitPct()) {
                    report("BUDGET_SOFT_LIMIT_REACHED", budget, now);
                }
            }
            return allowed;
        } catch (RuntimeException e) {
            count("dynamic.ai.agent.budget.check.errors");
            LOG.warn("Budget check failed for workspace {} (failOpen={}); {} the turn", key.workspaceId(),
                    failOpen, failOpen ? "allowing" : "refusing", e);
            return failOpen;
        }
    }

    private void report(String action, BudgetView budget, Instant now) {
        Instant windowStart = budget.period().windowContaining(now).from();
        String dedupe = budget.id() + ":" + windowStart + ":" + action;
        if (reported.size() >= MAX_ENTRIES) {
            reported.clear();
        }
        if (!reported.add(dedupe)) {
            return;
        }
        count("dynamic.ai.agent.budget.events", "kind", action, "scope", budget.target().scope().name());
        if (auditTrail == null) {
            return;
        }
        try {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("budgetId", budget.id().toString());
            details.put("scope", budget.target().scope().name());
            details.put("period", budget.period().name());
            details.put("hardLimit", budget.limits().hardLimit());
            auditTrail.append(AuditEventDraft.system(action, BudgetMath.workspaceOf(budget.target()),
                    CanonicalJson.write(details)));
        } catch (RuntimeException e) {
            LOG.warn("Audit append failed for {} (budget {})", action, budget.id(), e);
        }
    }

    private static void count(String name, String... tags) {
        try {
            Metrics.counter(name, tags).increment();
        } catch (LinkageError | RuntimeException e) {
            // metrics are optional; never affect a turn
        }
    }
}
