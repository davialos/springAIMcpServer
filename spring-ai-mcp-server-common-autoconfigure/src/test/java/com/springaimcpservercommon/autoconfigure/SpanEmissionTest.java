package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.query.ast.QueryDefinition;
import com.springaimcpservercommon.query.execution.QueryExecutor;
import com.springaimcpservercommon.query.execution.QueryResult;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpanEmissionTest {

    private final List<Observation.Context> stopped = new ArrayList<>();
    private final ObservationRegistry registry = ObservationRegistry.create();

    SpanEmissionTest() {
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                stopped.add(context);
            }
        });
    }

    private QueryDefinition query() {
        QueryDefinition q = mock(QueryDefinition.class);
        when(q.id()).thenReturn(UUID.randomUUID());
        when(q.workspaceId()).thenReturn(UUID.randomUUID());
        return q;
    }

    private static QueryResult run(QueryExecutor executor, QueryDefinition q) {
        return executor.execute(q, mock(DaiPrincipal.class), Map.of(), List.of(), mock(EffectiveCatalog.class), 10);
    }

    @Test
    void aQueryIsOneSpanWithIdsAndRowCountButNoContent() {
        QueryResult result = QueryResult.complete(List.of(Map.of("secret", "value")));

        run(new ObservedQueryExecutor((qq, p, params, policies, c, size) -> result, registry), query());

        assertThat(stopped).singleElement().satisfies(c -> {
            assertThat(c.getContextualName()).isEqualTo("dai.query");
            assertThat(c.getName()).isEqualTo("dynamic.ai.agent.query");
            assertThat(c.getLowCardinalityKeyValue("dai.query.outcome").getValue()).isEqualTo("ok");
            assertThat(c.getHighCardinalityKeyValue("dai.query.rows").getValue()).isEqualTo("1");
            assertThat(c.getHighCardinalityKeyValues().stream().map(kv -> kv.getValue()))
                    .noneMatch(v -> v.contains("secret") || v.contains("value"));
        });
    }

    @Test
    void aFailingQueryMarksTheSpanAsAnError() {
        var executor = new ObservedQueryExecutor((qq, p, params, policies, c, size) -> {
            throw new IllegalStateException("db down");
        }, registry);

        assertThatThrownBy(() -> run(executor, query())).isInstanceOf(IllegalStateException.class);

        assertThat(stopped).singleElement().satisfies(c -> {
            assertThat(c.getError()).isNotNull();
            assertThat(c.getLowCardinalityKeyValue("dai.query.outcome").getValue()).isEqualTo("error");
        });
    }

    @Test
    void aSnapshotApplyIsOneSpanWithCacheGenerationAndOutcome() {
        try (SnapshotObservations.Span span = SnapshotObservations.open(registry, "agents", 7)) {
            span.outcome("applied");
        }
        SnapshotObservations.Span failed = SnapshotObservations.open(registry, "queries", 8);
        failed.fail(new IllegalStateException("bad spec"));
        failed.close();

        assertThat(stopped).hasSize(2);
        assertThat(stopped.get(0).getContextualName()).isEqualTo("dai.snapshot.apply");
        assertThat(stopped.get(0).getLowCardinalityKeyValue("dai.snapshot.cache").getValue()).isEqualTo("agents");
        assertThat(stopped.get(0).getHighCardinalityKeyValue("dai.snapshot.generation").getValue()).isEqualTo("7");
        assertThat(stopped.get(0).getLowCardinalityKeyValue("dai.snapshot.outcome").getValue()).isEqualTo("applied");
        assertThat(stopped.get(1).getLowCardinalityKeyValue("dai.snapshot.outcome").getValue()).isEqualTo("failed");
        assertThat(stopped.get(1).getError()).isNotNull();
    }

    @Test
    void withoutARegistryTheSnapshotSpanCostsNothing() {
        try (SnapshotObservations.Span span = SnapshotObservations.open(null, "agents", 1)) {
            span.outcome("missing");
        }
        try (SnapshotObservations.Span span = SnapshotObservations.open(ObservationRegistry.NOOP, "agents", 1)) {
            span.fail(new IllegalStateException());
        }
        assertThat(stopped).isEmpty();
    }
}
