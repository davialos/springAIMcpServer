package com.springaimcpservercommon.core.catalog;

import com.springaimcpservercommon.annotations.Classification;

import java.util.List;
import java.util.Objects;

/**
 * An operation after policy merging (LLD-03 §2). The tool bridge builds tool callbacks per request from enabled
 * operations of the current generation.
 *
 * @param ref            {@code op:} reference
 * @param descriptor     scanned (code) descriptor: bean name, invocation type, method, schemas
 * @param toolName       tool name (code only)
 * @param description    effective description (intent or latest non-empty override)
 * @param keywords       effective keywords
 * @param readOnly       effective read-only flag; never loosened by a layer
 * @param classification effective classification
 * @param maxLimit       effective cap for the action's limit parameter: min of layers and the global cap
 * @param enabled        {@code false} if any layer disabled it (or a layer demanded read-only for a write)
 * @param provenance     changes applied by policy layers, in merge order
 */
public record EffectiveOperation(CatalogElementRef ref, OperationDescriptor descriptor, String toolName,
                                 String description, List<String> keywords, boolean readOnly,
                                 Classification classification, int maxLimit, boolean enabled,
                                 List<PolicyProvenance> provenance) {

    /** Validates components and copies collections. */
    public EffectiveOperation {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(classification, "classification");
        if (classification == Classification.INHERIT) {
            throw new IllegalArgumentException("effective classification must be concrete: " + ref);
        }
        if (maxLimit < 1) {
            throw new IllegalArgumentException("maxLimit must be >= 1: " + ref);
        }
        keywords = List.copyOf(keywords);
        provenance = List.copyOf(provenance);
    }

    /**
     * Parameters (code only).
     *
     * @return parameters in declaration order
     */
    public List<ParamDescriptor> params() {
        return descriptor.params();
    }

    /**
     * Input JSON schema (code only).
     *
     * @return the schema
     */
    public JsonSchema inputSchema() {
        return descriptor.inputSchema();
    }

    /**
     * Whether repeating the call has no additional effect (code only).
     *
     * @return idempotency flag
     */
    public boolean idempotent() {
        return descriptor.idempotent();
    }

    /**
     * Bean the action is invoked on (always through its proxy, ADR-0008).
     *
     * @return bean name
     */
    public String beanName() {
        return descriptor.beanName();
    }
}
