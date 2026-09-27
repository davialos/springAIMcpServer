package com.springaimcpservercommon.annotations;

/**
 * Data classification used for attribute-based access control, masking and model-provider routing
 * (SEC-01 §7, LLD-06 §6).
 *
 * <p>Ordered from least to most sensitive, so {@link #compareTo} expresses "more sensitive than".
 * {@link #INHERIT} means "use the classification of the enclosing type" and is only valid on members.
 */
public enum Classification {

    /** Use the classification of the enclosing type (members only). */
    INHERIT,

    /** May be shown to anyone who can call the tool. */
    PUBLIC,

    /** Internal business data; the default for types. */
    INTERNAL,

    /** Personal or commercially sensitive data; approvals required for writes. */
    CONFIDENTIAL,

    /** Highly regulated data; only on-prem/approved model providers, four-eyes approvals. */
    RESTRICTED;

    /**
     * Resolves {@link #INHERIT} against the enclosing classification.
     *
     * @param enclosing classification of the enclosing type, never {@link #INHERIT}
     * @return the effective classification
     */
    public Classification resolve(Classification enclosing) {
        if (enclosing == INHERIT) {
            throw new IllegalArgumentException("enclosing classification must not be INHERIT");
        }
        return this == INHERIT ? enclosing : this;
    }

    /**
     * Returns the more sensitive of the two classifications (used when merging policy layers, LLD-03 §4.1).
     *
     * @param other another effective classification
     * @return the stricter classification
     */
    public Classification max(Classification other) {
        return this.compareTo(other) >= 0 ? this : other;
    }
}
