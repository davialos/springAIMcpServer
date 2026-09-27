package com.springaimcpservercommon.persistence.audit;

/**
 * SPI to the host's KMS / vault for envelope encryption of audit evidence (ADR-0018; named
 * {@code EvidenceKeyProvider} in LLD-10 §4.1). A per-subject 256-bit data key is generated and wrapped by a key
 * encryption key (KEK) that never leaves the KMS; only the wrapped form is stored ({@code dai_evidence_subject_key}).
 * Crypto-shredding deletes the wrapped key, so no provider call can recover the data key afterwards.
 *
 * <p>Implementations must be thread-safe, apply their own timeouts, and never log key material. They may cache
 * unwrapped keys briefly; the library does not.
 */
public interface DataKeyProvider {

    /**
     * Generates a new 256-bit data key for a subject and wraps it with the current KEK.
     *
     * @param subjectKeyId subject key id (bound into the wrapping as context where the KMS supports it)
     * @return plaintext and wrapped key with the KEK reference
     */
    GeneratedDataKey generateDataKey(String subjectKeyId);

    /**
     * Unwraps a stored data key.
     *
     * @param kekRef       reference of the KEK that wrapped it
     * @param wrappedKey   wrapped key bytes
     * @param subjectKeyId subject key id (the same context as at generation)
     * @return the 32-byte plaintext data key (the caller wipes it after use)
     */
    byte[] unwrap(String kekRef, byte[] wrappedKey, String subjectKeyId);

    /**
     * A generated data key. Arrays are not copied; the plaintext key is wiped by the caller after use.
     *
     * @param kekRef       reference of the wrapping KEK (stored as {@code kek_ref})
     * @param plaintextKey 32-byte data key
     * @param wrappedKey   wrapped data key (stored as {@code wrapped_key})
     */
    record GeneratedDataKey(String kekRef, byte[] plaintextKey, byte[] wrappedKey) {
    }
}
