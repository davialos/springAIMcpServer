package com.springaimcpservercommon.security.authz;

import org.springframework.security.authorization.AuthorizationDecision;

import java.io.Serial;

/**
 * Spring Security {@link AuthorizationDecision} carrying the framework's {@link AuthorizationOutcome}, so that access
 * denied handlers can map the reason code to a problem response.
 */
public final class DaiAuthorizationDecision extends AuthorizationDecision {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient AuthorizationOutcome outcome;

    /**
     * Creates the decision.
     *
     * @param outcome engine outcome
     */
    public DaiAuthorizationDecision(AuthorizationOutcome outcome) {
        super(outcome.granted());
        this.outcome = outcome;
    }

    /**
     * Returns the engine outcome.
     *
     * @return outcome
     */
    public AuthorizationOutcome outcome() {
        return outcome;
    }

    @Override
    public String toString() {
        return "DaiAuthorizationDecision[granted=" + isGranted() + ", outcome=" + outcome + "]";
    }
}
