package com.springaimcpservercommon.loadtest.model;

import org.jspecify.annotations.Nullable;

/**
 * One property of an object schema.
 *
 * @param schema      value schema
 * @param required    whether the property must be present
 * @param sensitive   personal or secret data: never sampled from the database (ADR-0022 §4)
 * @param description free-text description, if declared
 */
public record Property(Schema schema, boolean required, boolean sensitive, @Nullable String description) {
}
