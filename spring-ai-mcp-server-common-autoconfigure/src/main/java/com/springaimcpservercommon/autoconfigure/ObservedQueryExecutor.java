package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.query.ast.QueryDefinition;
import com.springaimcpservercommon.query.execution.QueryExecutor;
import com.springaimcpservercommon.query.execution.QueryResult;
import com.springaimcpservercommon.query.ast.RowPolicy;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.NullMarked;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Wraps a {@link QueryExecutor} in the {@code dai.query} span / {@code dynamic.ai.agent.query} timer (LLD-10 §3).
 * Attributes are ids and counts only: never the filter, the parameters or any row (content is never attached).
 */
@NullMarked
final class ObservedQueryExecutor implements QueryExecutor {

    private final QueryExecutor delegate;
    private final ObservationRegistry registry;

    ObservedQueryExecutor(QueryExecutor delegate, ObservationRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    @SuppressWarnings("try") // the scope is held open so the query's own work nests under the span
    public QueryResult execute(QueryDefinition query, DaiPrincipal principal, Map<String, Object> params,
                               List<RowPolicy> rowPolicies, EffectiveCatalog catalog, int requestedSize) {
        Observation observation = Observation.createNotStarted("dynamic.ai.agent.query", registry)
                .contextualName("dai.query")
                .highCardinalityKeyValue("dai.query.id", query.id().toString())
                .highCardinalityKeyValue("dai.workspace.id", query.workspaceId().toString())
                .start();
        String outcome = "error";
        try (Observation.Scope ignored = observation.openScope()) {
            QueryResult result = delegate.execute(query, principal, params, rowPolicies, catalog, requestedSize);
            outcome = result.truncated() ? "truncated" : "ok";
            observation.highCardinalityKeyValue("dai.query.rows", String.valueOf(result.rowCount()));
            return result;
        } catch (RuntimeException e) {
            observation.error(e);
            throw e;
        } finally {
            observation.lowCardinalityKeyValue("dai.query.outcome", outcome);
            observation.stop();
        }
    }
}
