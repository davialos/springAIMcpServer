package com.springaimcpservercommon.persistence.usage;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A model price version ({@code dai_model_price}, LLD-10 §5). Versioned by {@code valid_from}: the price of a call is
 * the row with the greatest {@code valid_from <= call time}. Rows are never updated; a price change is a new row.
 */
@Entity
@Immutable
@Table(name = "dai_model_price")
public class ModelPrice {

    @EmbeddedId
    private ModelPriceId id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "input_per_mtok_micros", nullable = false, updatable = false)
    private long inputPerMtokMicros;

    @Column(name = "output_per_mtok_micros", nullable = false, updatable = false)
    private long outputPerMtokMicros;

    @Column(name = "cached_input_per_mtok_micros", nullable = false, updatable = false)
    private long cachedInputPerMtokMicros;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private @Nullable UUID createdBy;

    /** For JPA only. */
    protected ModelPrice() {
    }

    private ModelPrice(ModelPriceId id, String currency, long input, long output, long cachedInput,
                       @Nullable UUID createdBy, Instant now) {
        this.id = id;
        this.currency = currency;
        this.inputPerMtokMicros = input;
        this.outputPerMtokMicros = output;
        this.cachedInputPerMtokMicros = cachedInput;
        this.createdAt = now;
        this.createdBy = createdBy;
    }

    /**
     * Creates a price version.
     *
     * @param provider                 model provider
     * @param model                    model name
     * @param validFrom                start of validity
     * @param currency                 ISO-4217 code
     * @param inputPerMtokMicros       price per million input tokens, in micros of the currency
     * @param outputPerMtokMicros      price per million output tokens
     * @param cachedInputPerMtokMicros price per million cached input tokens
     * @param createdBy                creating principal
     * @param now                      creation time
     * @return the new price (not yet persisted)
     */
    public static ModelPrice create(String provider, String model, Instant validFrom, String currency,
                                    long inputPerMtokMicros, long outputPerMtokMicros, long cachedInputPerMtokMicros,
                                    @Nullable UUID createdBy, Instant now) {
        requireText(provider, "provider");
        requireText(model, "model");
        if (inputPerMtokMicros < 0 || outputPerMtokMicros < 0 || cachedInputPerMtokMicros < 0) {
            throw new IllegalArgumentException("prices must not be negative");
        }
        return new ModelPrice(new ModelPriceId(provider, model, validFrom), Currencies.require(currency),
                inputPerMtokMicros, outputPerMtokMicros, cachedInputPerMtokMicros, createdBy,
                Objects.requireNonNull(now, "now"));
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 200) {
            throw new IllegalArgumentException(name + " must have 1..200 characters");
        }
    }

    /**
     * Immutable snapshot of this row.
     *
     * @return the view
     */
    public ModelPriceView view() {
        return new ModelPriceView(id.getProvider(), id.getModel(), id.getValidFrom(), currency, inputPerMtokMicros,
                outputPerMtokMicros, cachedInputPerMtokMicros);
    }

    public ModelPriceId getId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof ModelPrice other && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
