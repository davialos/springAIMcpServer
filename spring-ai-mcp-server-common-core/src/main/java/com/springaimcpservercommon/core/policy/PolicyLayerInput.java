package com.springaimcpservercommon.core.policy;

import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.catalog.PolicyLayer;

import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * One loaded policy layer handed to the {@link PolicyMerger}. A layer is either a valid document, an invalid
 * source (fail closed), or the kill-switch set.
 */
public sealed interface PolicyLayerInput
        permits PolicyLayerInput.DocumentLayer, PolicyLayerInput.InvalidLayer, PolicyLayerInput.KillSwitchLayer {

    /**
     * The layer.
     *
     * @return the layer
     */
    PolicyLayer layer();

    /**
     * Source id (file location, overlay revision id, kill-switch set id) recorded in provenance.
     *
     * @return the source id
     */
    String sourceId();

    /**
     * A valid FILE or OVERLAY document.
     *
     * @param layer    {@link PolicyLayer#FILE} or {@link PolicyLayer#OVERLAY}
     * @param sourceId source id
     * @param document the parsed document
     */
    record DocumentLayer(PolicyLayer layer, String sourceId, PolicyDocument document) implements PolicyLayerInput {

        /** Validates components; {@code declassify} is only allowed in OVERLAY documents. */
        public DocumentLayer {
            Objects.requireNonNull(layer, "layer");
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(document, "document");
            if (layer != PolicyLayer.FILE && layer != PolicyLayer.OVERLAY) {
                throw new IllegalArgumentException("document layers are FILE or OVERLAY, not " + layer);
            }
            if (layer != PolicyLayer.OVERLAY && document.overrides().values().stream().anyMatch(PolicyOverride::declassify)) {
                throw new IllegalArgumentException("declassify is only allowed in OVERLAY documents");
            }
        }
    }

    /**
     * A layer whose source exists but could not be read or validated. The merger disables every operation and
     * entity (the source may contain disables that must not be lost, LLD-03 §4.3).
     *
     * @param layer    the layer
     * @param sourceId source id
     * @param error    safe error summary (no values)
     */
    record InvalidLayer(PolicyLayer layer, String sourceId, String error) implements PolicyLayerInput {

        /** Validates components. */
        public InvalidLayer {
            Objects.requireNonNull(layer, "layer");
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(error, "error");
            if (layer == PolicyLayer.CODE) {
                throw new IllegalArgumentException("CODE is not a policy input layer");
            }
        }
    }

    /**
     * Runtime kill switches (L3, F-73).
     *
     * @param sourceId  kill-switch set id / version
     * @param global    disables every operation and entity
     * @param toolNames tool names to disable
     * @param elements  element refs to disable; an entity ref also disables operations returning that entity
     * @param reason    why (mandatory)
     */
    record KillSwitchLayer(String sourceId, boolean global, Set<String> toolNames, Set<CatalogElementRef> elements,
                           String reason) implements PolicyLayerInput {

        /** Validates components and copies sets into a sorted, immutable form. */
        public KillSwitchLayer {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("kill switch reason is mandatory");
            }
            toolNames = Set.copyOf(new TreeSet<>(toolNames));
            elements = Set.copyOf(elements);
        }

        @Override
        public PolicyLayer layer() {
            return PolicyLayer.KILL_SWITCH;
        }
    }
}
