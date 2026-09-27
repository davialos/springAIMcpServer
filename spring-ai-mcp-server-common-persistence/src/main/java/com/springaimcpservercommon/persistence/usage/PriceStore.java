package com.springaimcpservercommon.persistence.usage;

import com.springaimcpservercommon.persistence.unit.DaiStore;
import jakarta.persistence.EntityManager;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Versioned model prices (LLD-10 §5, PLATFORM_ADMIN only in the admin API).
 */
public final class PriceStore {

    private final DaiStore store;
    private final Clock clock;

    /**
     * Creates the store with the system UTC clock.
     *
     * @param store the persistence unit
     */
    public PriceStore(DaiStore store) {
        this(store, Clock.systemUTC());
    }

    /**
     * Creates the store.
     *
     * @param store the persistence unit
     * @param clock time source
     */
    public PriceStore(DaiStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Adds a price version. A second version with the same provider, model and {@code validFrom} violates the
     * primary key.
     *
     * @param provider                 model provider
     * @param model                    model name
     * @param validFrom                start of validity
     * @param currency                 ISO-4217 code
     * @param inputPerMtokMicros       price per million input tokens (micros)
     * @param outputPerMtokMicros      price per million output tokens (micros)
     * @param cachedInputPerMtokMicros price per million cached input tokens (micros)
     * @param createdBy                creating principal
     * @return the stored price
     */
    public ModelPriceView addPrice(String provider, String model, Instant validFrom, String currency,
                                   long inputPerMtokMicros, long outputPerMtokMicros, long cachedInputPerMtokMicros,
                                   @Nullable UUID createdBy) {
        ModelPrice price = ModelPrice.create(provider, model, validFrom, currency, inputPerMtokMicros,
                outputPerMtokMicros, cachedInputPerMtokMicros, createdBy, clock.instant());
        return store.transactions().execute(status -> {
            EntityManager em = store.entityManager();
            em.persist(price);
            em.flush();
            return price.view();
        });
    }

    /**
     * The price in effect at an instant: greatest {@code valid_from <= at}.
     *
     * @param provider model provider
     * @param model    model name
     * @param at       call time
     * @return the price, empty when the model is unpriced at that time
     */
    public Optional<ModelPriceView> priceAt(String provider, String model, Instant at) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(at, "at");
        return store.readOnlyTransactions().execute(status -> store.entityManager()
                .createQuery("select p from ModelPrice p where p.id.provider = :provider and p.id.model = :model"
                        + " and p.id.validFrom <= :at order by p.id.validFrom desc", ModelPrice.class)
                .setParameter("provider", provider)
                .setParameter("model", model)
                .setParameter("at", at)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .map(ModelPrice::view));
    }

    /**
     * All price versions, for the admin UI and for caching in the metering writer.
     *
     * @return prices ordered by provider, model and validity
     */
    public List<ModelPriceView> list() {
        return store.readOnlyTransactions().execute(status -> store.entityManager()
                .createQuery("select p from ModelPrice p order by p.id.provider, p.id.model, p.id.validFrom",
                        ModelPrice.class)
                .getResultStream()
                .map(ModelPrice::view)
                .toList());
    }
}
