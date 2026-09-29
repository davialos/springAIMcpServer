package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.advisor.UsageMeteringAdvisor;
import com.springaimcpservercommon.ai.agent.AgentDefinition;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.persistence.usage.ModelPriceView;
import com.springaimcpservercommon.persistence.usage.PriceStore;
import com.springaimcpservercommon.persistence.usage.UsageDelta;
import com.springaimcpservercommon.persistence.usage.UsageLedger;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Records the tokens and cost of each completed model call in the hourly usage ledger (F-70, F-71), which
 * feeds budgets and the usage dashboard.
 *
 * <p>The model is the agent's configured one; a call served by a fallback model is attributed to the
 * primary model (the usage callback does not carry the serving model). Cost comes from the price valid at
 * call time; without a price the call is recorded with tokens only and no cost, so a cost budget cannot
 * fire for that model until a price is added. Prices are cached briefly per node. Cached-input tokens are
 * not reported by the callback and are recorded as zero.
 *
 * <p>Failures propagate to the caller, which logs them without failing the turn.
 */
@NullMarked
final class LedgerUsageSink implements UsageMeteringAdvisor.UsageSink {

    private static final int MAX_PRICES = 1_000;

    private record PriceKey(String provider, String model) {}

    private record CachedPrice(@Nullable ModelPriceView price, Instant expiresAt) {}

    private final UsageLedger ledger;
    private final PriceStore prices;
    private final Duration priceCacheTtl;
    private final Clock clock;
    private final ConcurrentMap<PriceKey, CachedPrice> priceCache = new ConcurrentHashMap<>();

    LedgerUsageSink(UsageLedger ledger, PriceStore prices, Duration priceCacheTtl, Clock clock) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.prices = Objects.requireNonNull(prices, "prices");
        this.priceCacheTtl = Objects.requireNonNull(priceCacheTtl, "priceCacheTtl");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void record(AgentDefinition agent, DaiPrincipal principal, long promptTokens, long completionTokens) {
        Instant now = clock.instant();
        String provider = agent.model().providerId();
        String model = agent.model().modelName();
        ModelPriceView price = priceAt(provider, model, now);
        long cost = price == null ? 0L : price.costMicros(promptTokens, completionTokens, 0L);
        ledger.record(new UsageDelta(now, agent.workspaceId(), agent.id(), principal.principalId(), provider, model,
                price == null ? null : price.currency(), 1, promptTokens, completionTokens, 0L, cost));
    }

    private @Nullable ModelPriceView priceAt(String provider, String model, Instant now) {
        PriceKey key = new PriceKey(provider, model);
        CachedPrice cached = priceCache.get(key);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.price();
        }
        Optional<ModelPriceView> found = prices.priceAt(provider, model, now);
        if (priceCache.size() >= MAX_PRICES) {
            priceCache.clear();
        }
        priceCache.put(key, new CachedPrice(found.orElse(null), now.plus(priceCacheTtl)));
        return found.orElse(null);
    }
}
