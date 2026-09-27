package com.springaimcpservercommon.persistence.audit;

import java.util.Objects;

/**
 * A stored, wrapped per-subject data key as read from {@code dai_evidence_subject_key}.
 *
 * @param subjectKeyId subject key id
 * @param kekRef       KEK reference
 * @param wrappedKey   wrapped key bytes (not copied)
 */
public record WrappedDataKey(String subjectKeyId, String kekRef, byte[] wrappedKey) {

    /**
     * Validates the components.
     */
    public WrappedDataKey {
        Objects.requireNonNull(subjectKeyId, "subjectKeyId");
        Objects.requireNonNull(kekRef, "kekRef");
        Objects.requireNonNull(wrappedKey, "wrappedKey");
    }
}
