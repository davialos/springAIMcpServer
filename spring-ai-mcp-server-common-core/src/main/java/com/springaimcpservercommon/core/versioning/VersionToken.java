package com.springaimcpservercommon.core.versioning;

import java.util.Objects;

/**
 * A comparable marker of one record's state in the host: what a change was proposed against, or what a change
 * produced. Two tokens of the same kind are equal exactly when the record is in the same version.
 *
 * @param kind  how the host versions the record
 * @param value the marker, at most {@value #MAX_VALUE_LENGTH} characters
 */
public record VersionToken(Kind kind, String value) {

    /** Longest value a token may carry (the proposal store column limit). */
    public static final int MAX_VALUE_LENGTH = 512;

    /** How a record is versioned; the names match the proposal store's base version kinds. */
    public enum Kind {
        /** The entity's JPA {@code @Version} attribute. */
        JPA_VERSION,
        /** The latest Envers revision number of the entity. */
        ENVERS_REVISION,
        /** The version column of a history table mapped by configuration. */
        HISTORY_TABLE,
        /** The period start of a system-versioned (temporal) table. */
        TEMPORAL,
        /** A hash of the entity's exposed, non-sensitive attributes (the fallback). */
        ROW_HASH,
        /** A host-specific marker. */
        CUSTOM
    }

    /** Validates the components. */
    public VersionToken {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
        if (value.isBlank() || value.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException("a version value must have 1.." + MAX_VALUE_LENGTH + " characters");
        }
    }

    /** @return {@code kind:value}, the form stored as a host revision reference */
    public String asReference() {
        return kind.name() + ":" + value;
    }
}
