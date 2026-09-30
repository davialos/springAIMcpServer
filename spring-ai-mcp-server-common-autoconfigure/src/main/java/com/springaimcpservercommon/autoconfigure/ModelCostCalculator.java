package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.persistence.usage.ModelPriceView;
import com.springaimcpservercommon.persistence.usage.PriceStore;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Prices model usage from the price history, shared by usage recording and turn recording so a turn's model
 * call and its ledger entry carry the same cost. The price valid at the given instant is cached per
 * (provider, model) for a short time to keep the lookup off the hot path; the cache is node-local (ADR-0021).
 * Without a price the cost is zero and has no currency (tokens are still recorded).
 */
@NullMarked
final class ModelCostCalculator {

    private static final int MAX_PRICES = 1_000;

    /**
     * A priced amount.
     *
     * @param micros   cost in currency micros (0 when unpriced)
     * @param currency ISO-4217 code, or {@code null} when unpriced
     */
    record Cost(long micros, @Nullable String currency) {
        /** No price known. */
        static final Cost NONE = new Cost(0L, null);
    }

    private record PriceKey(String provider, String model) {}

    private record CachedPrice(@Nullable ModelPriceView price, Instant expiresAt) {}

    private final PriceStore prices;
    private final Duration cacheTtl;
    private final ConcurrentMap<PriceKey, CachedPrice> cache = new ConcurrentHashMap<>();

    ModelCostCalculator(PriceStore prices, Duration cacheTtl) {
        this.prices = Objects.requireNonNull(prices, "prices");
        this.cacheTtl = Objects.requireNonNull(cacheTtl, "cacheTtl");
    }

    /**
     * Prices token usage.
     *
     * @param provider provider id
     * @param model    model id
     * @param input    input tokens
     * @param output   output tokens
     * @param cached   cached input tokens
     * @param at       instant whose price applies
     * @return the cost, or {@link Cost#NONE} when the model has no price at that time
     */
    Cost cost(String provider, String model, long input, long output, long cached, Instant at) {
        ModelPriceView price = priceAt(provider, model, at);
        return price == null ? Cost.NONE : new Cost(price.costMicros(input, output, cached), price.currency());
    }

    private @Nullable ModelPriceView priceAt(String provider, String model, Instant at) {
        PriceKey key = new PriceKey(provider, model);
        CachedPrice cached = cache.get(key);
        if (cached != null && cached.expiresAt().isAfter(at)) {
            return cached.price();
        }
        Optional<ModelPriceView> found = prices.priceAt(provider, model, at);
        if (cache.size() >= MAX_PRICES) {
            cache.clear();
        }
        cache.put(key, new CachedPrice(found.orElse(null), at.plus(cacheTtl)));
        return found.orElse(null);
    }
}
