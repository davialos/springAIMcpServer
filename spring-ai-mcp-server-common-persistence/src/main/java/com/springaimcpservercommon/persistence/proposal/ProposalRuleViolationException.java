package com.springaimcpservercommon.persistence.proposal;

import java.io.Serial;
import java.util.Objects;

/**
 * A request violated a rule of the proposal lifecycle (LLD-11 §3, §9). The {@link Reason} lets the web layer map the
 * failure to a problem type (e.g. EXPIRED → 410, STALE_VERSION → 412, the others → 409 or 403).
 */
public final class ProposalRuleViolationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Which rule was violated. */
    public enum Reason {
        /** The transition is not allowed from the current state. */
        ILLEGAL_TRANSITION,
        /** Only the proposal owner may do this. */
        NOT_OWNER,
        /** The owner may not approve or reject their own proposal (segregation of duties). */
        OWNER_CANNOT_APPROVE,
        /** The confirmed content hash differs from the proposal's current content. */
        CONTENT_HASH_MISMATCH,
        /** The proposal has expired. */
        EXPIRED,
        /** This approver has already decided. */
        ALREADY_DECIDED,
        /** An idempotency key was reused for different content. */
        IDEMPOTENCY_KEY_REUSED,
        /** The caller's expected row version is stale (If-Match failed or a concurrent update won). */
        STALE_VERSION
    }

    private final Reason reason;

    /**
     * Creates the exception.
     *
     * @param reason  violated rule
     * @param message detail message (no user content)
     */
    public ProposalRuleViolationException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Creates the exception with a cause.
     *
     * @param reason  violated rule
     * @param message detail message (no user content)
     * @param cause   cause
     */
    public ProposalRuleViolationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * The violated rule.
     *
     * @return reason
     */
    public Reason reason() {
        return reason;
    }
}
