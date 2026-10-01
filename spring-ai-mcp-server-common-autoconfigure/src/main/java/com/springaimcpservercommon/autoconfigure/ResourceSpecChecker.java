package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.persistence.config.ResourceKind;

import java.util.List;

/**
 * Checks the content of a resource spec when an author saves it, so mistakes show up as validation errors then and
 * not when the spec is first served (OQ-41). Implementations must not throw for bad specs: they answer the
 * violations in plain English.
 */
@FunctionalInterface
public interface ResourceSpecChecker {

    /** A checker that accepts everything. */
    ResourceSpecChecker NONE = (kind, specJson) -> List.of();

    /**
     * Checks a spec.
     *
     * @param kind     the kind of resource
     * @param specJson the spec, already known to be a well-formed JSON object without credentials
     * @return violations, empty when the spec is acceptable
     */
    List<String> violations(ResourceKind kind, String specJson);
}
