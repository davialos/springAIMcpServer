package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.advisor.UsageMeteringAdvisor;
import com.springaimcpservercommon.ai.agent.AgentDefinition;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.persistence.usage.UsageDelta;
import com.springaimcpservercommon.persistence.usage.UsageLedger;
import org.jspecify.annotations.NullMarked;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Records the tokens and cost of each completed model call in the hourly usage ledger (F-70, F-71), which
 * feeds budgets and the usage dashboard.
 *
 * <p>The model is the agent's configured one; a call served by a fallback model is attributed to the
 * primary model (the usage callback does not carry the serving model). Cost comes from the price valid at
 * call time; without a price the call is recorded with tokens only and no cost, so a cost budget cannot
 * fire for that model until a price is added (see {@link ModelCostCalculator}). Cached-input tokens are
 * not reported by the callback and are recorded as zero.
 *
 * <p>Failures propagate to the caller, which logs them without failing the turn.
 */
@NullMarked
final class LedgerUsageSink implements UsageMeteringAdvisor.UsageSink {

    private final UsageLedger ledger;
    private final ModelCostCalculator costs;
    private final Clock clock;

    LedgerUsageSink(UsageLedger ledger, ModelCostCalculator costs, Clock clock) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.costs = Objects.requireNonNull(costs, "costs");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void record(AgentDefinition agent, DaiPrincipal principal, long promptTokens, long completionTokens) {
        Instant now = clock.instant();
        String provider = agent.model().providerId();
        String model = agent.model().modelName();
        ModelCostCalculator.Cost cost = costs.cost(provider, model, promptTokens, completionTokens, 0L, now);
        ledger.record(new UsageDelta(now, agent.workspaceId(), agent.id(), principal.principalId(), provider, model,
                cost.currency(), 1, promptTokens, completionTokens, 0L, cost.micros()));
    }
}
