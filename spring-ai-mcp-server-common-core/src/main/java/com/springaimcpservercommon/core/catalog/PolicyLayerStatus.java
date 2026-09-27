package com.springaimcpservercommon.core.catalog;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Outcome of one policy layer in a merge, for health contributors ({@code …policy.layer.valid{layer}}) and the
 * dashboard.
 *
 * @param layer       the layer
 * @param source      source id (location, revision, kill-switch set)
 * @param valid       whether the layer was applied normally; {@code false} ⇒ fail-closed
 * @param error       safe error summary when invalid
 * @param fingerprint {@code sha256:} of the layer's canonical content, when valid
 */
public record PolicyLayerStatus(PolicyLayer layer, String source, boolean valid, @Nullable String error,
                                @Nullable String fingerprint) {

    /** Validates components. */
    public PolicyLayerStatus {
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(source, "source");
    }
}
