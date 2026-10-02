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
 * @param maxLength length of the column behind the field (JPA {@code @Column(length)} / database), if known
 * @param unique    the column behind the field is unique: generated values get a per-request suffix
 */
public record FieldPlan(String key, String name, String owner, FieldKind kind, @Nullable PoolRef pool,
                        boolean sensitive, @Nullable Integer maxLength, boolean unique) {

    /**
     * A field without column facts.
     *
     * @param key       field key
     * @param name      field name
     * @param owner     owner
     * @param kind      kind
     * @param pool      pool, or {@code null}
     * @param sensitive sensitive
     */
    public FieldPlan(String key, String name, String owner, FieldKind kind, @Nullable PoolRef pool,
                     boolean sensitive) {
        this(key, name, owner, kind, pool, sensitive, null, false);
    }
}
