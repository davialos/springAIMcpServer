package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.query.ast.QueryDefinition;
import com.springaimcpservercommon.query.ast.RowPolicy;
import com.springaimcpservercommon.query.execution.QueryExecutor;
import com.springaimcpservercommon.query.execution.QueryResult;
import jakarta.persistence.EntityManagerFactory;
import org.jspecify.annotations.NullMarked;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runs the {@link QueryExecutor} inside a read-only transaction on the host's entity manager factory (LLD-05 §4).
 *
 * <p>{@code CriteriaQueryExecutor} declares {@code @Transactional(readOnly = true)}, but that annotation only works
 * when the call goes through a Spring proxy. The auto-configured executor is not always one: wrapped for tracing it
 * is a plain object, and tool calls run on a virtual thread where no request-bound (open-in-view) entity manager
 * exists, so the query found no transaction and failed. This wrapper makes the boundary explicit instead of depending
 * on proxying. It uses a private {@link JpaTransactionManager} over the same factory, never registered as a bean
 * (ADR-0019); because Spring binds the transaction resources by factory, a call made inside a host transaction on that
 * factory joins it ({@code PROPAGATION_REQUIRED}) instead of opening a second one.
 */
@NullMarked
final class TransactionalQueryExecutor implements QueryExecutor {

    private final QueryExecutor delegate;
    private final TransactionTemplate readOnly;

    TransactionalQueryExecutor(QueryExecutor delegate, EntityManagerFactory entityManagerFactory) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        JpaTransactionManager manager = new JpaTransactionManager(
                Objects.requireNonNull(entityManagerFactory, "entityManagerFactory"));
        manager.afterPropertiesSet();
        this.readOnly = new TransactionTemplate(manager);
        this.readOnly.setReadOnly(true);
        this.readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Override
    public QueryResult execute(QueryDefinition query, DaiPrincipal principal, Map<String, Object> params,
                               List<RowPolicy> rowPolicies, EffectiveCatalog catalog, int requestedSize) {
        return Objects.requireNonNull(readOnly.execute(status ->
                delegate.execute(query, principal, params, rowPolicies, catalog, requestedSize)));
    }
}
