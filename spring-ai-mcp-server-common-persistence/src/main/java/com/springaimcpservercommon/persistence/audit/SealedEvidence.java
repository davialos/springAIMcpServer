package com.springaimcpservercommon.persistence.audit;

import java.util.Objects;

/**
 * Encrypted evidence content as stored in {@code dai_audit_evidence}.
 *
 * @param nonce      AEAD nonce (96 bits for AES-GCM)
 * @param ciphertext ciphertext including the authentication tag
 */
public record SealedEvidence(byte[] nonce, byte[] ciphertext) {

    /**
     * Validates the components.
     */
    public SealedEvidence {
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(ciphertext, "ciphertext");
    }
}
