package com.springaimcpservercommon.core.policy;

import com.springaimcpservercommon.core.catalog.PolicyLayer;

/**
 * SPI: one source per policy layer location (LLD-03 §2) — classpath/file JSON, dashboard overlays, kill switches.
 * Implementations live in autoconfigure / persistence.
 *
 * <p>{@link #load()} must not throw: a present-but-broken source returns a
 * {@link PolicyLayerInput.InvalidLayer} (use {@link PolicyDocumentParser#parseLayer}), a missing optional source an
 * empty {@link PolicyLayerInput.DocumentLayer}.
 */
public interface PolicySource {

    /**
     * The layer this source feeds.
     *
     * @return the layer
     */
    PolicyLayer layer();

    /**
     * Loads the current content of the source.
     *
     * @return the layer input, never {@code null}
     */
    PolicyLayerInput load();
}
