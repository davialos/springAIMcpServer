package com.springaimcpservercommon.persistence.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/** Composite key of {@code dai_model_price}: (provider, model, valid_from). */
@Embeddable
public class ModelPriceId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Column(name = "provider", nullable = false, updatable = false)
    private String provider;

    @Column(name = "model", nullable = false, updatable = false)
    private String model;

    @Column(name = "valid_from", nullable = false, updatable = false)
    private Instant validFrom;

    /** For JPA only. */
    protected ModelPriceId() {
    }

    /**
     * Creates a key.
     *
     * @param provider  model provider (e.g. {@code openai})
     * @param model     model name
     * @param validFrom start of validity
     */
    public ModelPriceId(String provider, String model, Instant validFrom) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.model = Objects.requireNonNull(model, "model");
        this.validFrom = Objects.requireNonNull(validFrom, "validFrom");
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof ModelPriceId other && provider.equals(other.provider)
                && model.equals(other.model) && validFrom.equals(other.validFrom));
    }

    @Override
    public int hashCode() {
        return Objects.hash(provider, model, validFrom);
    }
}
