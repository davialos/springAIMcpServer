package com.springaimcpservercommon.persistence.audit;

import java.io.Serial;

/** Evidence failed authentication on decryption (tampered ciphertext, wrong key or wrong row context). */
public final class EvidenceIntegrityException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message detail (no content)
     * @param cause   cause
     */
    public EvidenceIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
