package com.springaimcpservercommon.persistence.config;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Authored content of a draft revision.
 *
 * @param specJson          the resource spec, a JSON object (canonicalised and hashed on write, see {@link CanonicalSpec})
 * @param specSchemaVersion version of the spec schema for its resource kind (≥ 1)
 * @param changeSummary     optional human summary of the change
 * @param scanFingerprint   catalog scan fingerprint the draft was authored against (LLD-02 §3.4), if known
 */
public record DraftContent(
        String specJson,
        int specSchemaVersion,
        @Nullable String changeSummary,
        @Nullable String scanFingerprint) {

    /** Validates components. */
    public DraftContent {
        Objects.requireNonNull(specJson, "specJson");
        if (specSchemaVersion < 1) {
            throw new IllegalArgumentException("specSchemaVersion must be >= 1");
        }
    }

    /**
     * Content with schema version 1 and no summary or fingerprint.
     *
     * @param specJson spec JSON object
     * @return the content
     */
    public static DraftContent of(String specJson) {
        return new DraftContent(specJson, 1, null, null);
    }

    @Override
    public String toString() {
        return "DraftContent[specSchemaVersion=" + specSchemaVersion + ", scanFingerprint=" + scanFingerprint + "]";
    }
}
