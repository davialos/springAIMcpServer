package com.springaimcpservercommon.persistence.audit;

import java.io.Serial;

/** Crypto-shredding was refused because an active legal hold preserves the subject's evidence. */
public final class LegalHoldActiveException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param subjectKeyId the held subject key
     */
    public LegalHoldActiveException(String subjectKeyId) {
        super("subject " + subjectKeyId + " is under an active legal hold");
    }
}
