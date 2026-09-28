package com.springaimcpservercommon.ai.agent;

import java.util.Objects;
import java.util.UUID;

/**
 * Reference to a published {@link com.springaimcpservercommon.ai.tool.ToolBinding} within an
 * {@link AgentDefinition} (LLD-06 §2).
 *
 * @param bindingId tool binding id
 * @param revision  binding revision; used for drift detection against the catalog
 */
public record ToolBindingRef(UUID bindingId, int revision) {

    /** Validates fields. */
    public ToolBindingRef {
        Objects.requireNonNull(bindingId, "bindingId");
        if (revision < 0) throw new IllegalArgumentException("revision must be >= 0");
    }
}
