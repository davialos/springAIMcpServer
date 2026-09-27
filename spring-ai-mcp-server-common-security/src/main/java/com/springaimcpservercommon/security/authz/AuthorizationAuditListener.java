package com.springaimcpservercommon.security.authz;

/**
 * SPI: receives every authorization decision, permits and denials alike (SEC-01 §1.6, §7). Implemented by the audit
 * module. Called synchronously on the deciding thread, so implementations must be fast and non-blocking (enqueue).
 * Exceptions are logged and never change the decision.
 */
@FunctionalInterface
public interface AuthorizationAuditListener {

    /**
     * Records a decision.
     *
     * @param event the decision
     */
    void onDecision(AuthorizationDecisionEvent event);
}
