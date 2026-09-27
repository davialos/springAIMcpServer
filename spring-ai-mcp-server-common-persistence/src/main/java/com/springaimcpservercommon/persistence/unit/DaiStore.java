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
     * The validated PostgreSQL schema of the unit (matches {@code ^[a-z_][a-z0-9_]{0,62}$}, default
     * {@code dynamic_ai}). Hibernate-mapped entities are qualified with it automatically
     * ({@code hibernate.default_schema}); native SQL must qualify every table and function with it, because the
     * runtime connection's {@code search_path} belongs to the host.
     *
     * @return the schema name, safe to concatenate into SQL
     */
    String schema();

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

    /**
     * The PostgreSQL schema holding the unit's tables and functions (validated against
     * {@code ^[a-z_][a-z0-9_]{0,62}$}, so it is safe to concatenate into native SQL). Hibernate qualifies mapped
     * entities itself; native SQL must qualify table and function names with this schema because the runtime
     * connection's {@code search_path} belongs to the host.
     *
     * @return schema name, e.g. {@code dynamic_ai}
     */
    String schema();
}
