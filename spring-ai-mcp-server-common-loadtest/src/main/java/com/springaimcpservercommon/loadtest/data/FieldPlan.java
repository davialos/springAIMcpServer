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
 * @param component position in a tuple pool (composite foreign key), or {@code -1}
 */
public record FieldPlan(String key, String name, String owner, FieldKind kind, @Nullable PoolRef pool,
                        boolean sensitive, @Nullable Integer maxLength, boolean unique, int component) {

    /**
     * A field bound to a single-column pool (or none).
     *
     * @param key       field key
     * @param name      field name
     * @param owner     owner
     * @param kind      kind
     * @param pool      pool, or {@code null}
     * @param sensitive sensitive
     * @param maxLength column length, or {@code null}
     * @param unique    unique column
     */
    public FieldPlan(String key, String name, String owner, FieldKind kind, @Nullable PoolRef pool,
                     boolean sensitive, @Nullable Integer maxLength, boolean unique) {
        this(key, name, owner, kind, pool, sensitive, maxLength, unique, -1);
    }

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
