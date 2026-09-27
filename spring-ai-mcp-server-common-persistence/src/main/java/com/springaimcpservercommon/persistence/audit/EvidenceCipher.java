package com.springaimcpservercommon.persistence.audit;

/**
 * Port for authenticated encryption of evidence content with a subject's wrapped data key. The associated data binds
 * a ciphertext to its row (evidence id, audit event, subject, content type), so ciphertexts cannot be swapped between
 * rows undetected.
 */
public interface EvidenceCipher {

    /**
     * Encrypts content.
     *
     * @param key            the subject's wrapped data key
     * @param plaintext      content to protect
     * @param associatedData authenticated, unencrypted context
     * @return nonce and ciphertext
     */
    SealedEvidence seal(WrappedDataKey key, byte[] plaintext, byte[] associatedData);

    /**
     * Decrypts and authenticates content.
     *
     * @param key            the subject's wrapped data key
     * @param sealed         nonce and ciphertext
     * @param associatedData the context used when sealing
     * @return the plaintext
     * @throws EvidenceIntegrityException if authentication fails (tampered ciphertext, wrong key or context)
     */
    byte[] open(WrappedDataKey key, SealedEvidence sealed, byte[] associatedData);
}
