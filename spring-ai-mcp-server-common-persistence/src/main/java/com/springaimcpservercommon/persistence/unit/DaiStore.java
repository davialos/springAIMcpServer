package com.springaimcpservercommon.persistence.unit;

import jakarta.persistence.EntityManager;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Access to the framework's isolated persistence unit (ADR-0019).
 *
 * <p>The entity manager factory, transaction manager and Flyway instance behind this interface are never
 * registered as Spring beans, so the host's JPA auto-configuration, {@code @Transactional} default transaction
 * manager and repositories are never affected. Every store in this module receives a {@code DaiStore} through
 * its constructor and uses these operations explicitly.
 */
public interface DaiStore {

    /**
     * A shared, transaction-bound {@link EntityManager} proxy for the {@code dynamic_ai} unit. It must only be
     * used inside {@link #transactions()} or {@link #readOnlyTransactions()} callbacks.
     *
     * @return the shared entity manager
     */
    EntityManager entityManager();

    /**
     * Read-write transactions on the unit's own {@code JpaTransactionManager} (propagation REQUIRED).
     *
     * @return transaction operations
     */
    TransactionOperations transactions();

    /**
     * Read-only transactions (Hibernate read-only session, {@code Connection.setReadOnly(true)}).
     *
     * @return read-only transaction operations
     */
    TransactionOperations readOnlyTransactions();

    /**
     * Transactions that always start a new physical transaction (propagation REQUIRES_NEW), used for audit
     * appends that must commit even when the surrounding work rolls back.
     *
     * @return transaction operations with REQUIRES_NEW propagation
     */
    TransactionOperations newTransactions();
}
