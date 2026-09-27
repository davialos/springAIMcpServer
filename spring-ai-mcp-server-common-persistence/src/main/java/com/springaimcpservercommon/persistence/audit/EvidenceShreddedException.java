package com.springaimcpservercommon.persistence.audit;

import java.io.Serial;

/** The subject's data key was crypto-shredded; its evidence can no longer be written or read. */
public final class EvidenceShreddedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param subjectKeyId the shredded subject key
     */
    public EvidenceShreddedException(String subjectKeyId) {
        super("evidence key of subject " + subjectKeyId + " has been shredded");
    }
}
