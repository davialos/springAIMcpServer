package com.springaimcpservercommon.loadtest.data;

import org.jspecify.annotations.Nullable;

/**
 * How one scalar request field gets its values.
 *
 * @param key       field key ({@link FieldKeys})
 * @param name      field name
 * @param owner     API id or schema name that owns the field
 * @param kind      semantic kind (drives the dummy generator)
 * @param pool      real-data pool bound to the field, or {@code null}
 * @param sensitive never filled from real data unless explicitly bound
 */
public record FieldPlan(String key, String name, String owner, FieldKind kind, @Nullable PoolRef pool,
                        boolean sensitive) {
}
