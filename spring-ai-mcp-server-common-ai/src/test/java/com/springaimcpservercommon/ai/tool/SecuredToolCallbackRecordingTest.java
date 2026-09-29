package com.springaimcpservercommon.ai.tool;

import com.springaimcpservercommon.ai.guard.AiWriteViolationException;
import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.hash.Sha256;
import com.springaimcpservercommon.core.invocation.Channel;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.core.principal.SubjectType;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.context.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.security.authentication.TestingAuthenticationToken;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class SecuredToolCallbackRecordingTest {

    private static final UUID TURN = UUID.randomUUID();
    private static final ToolDefinition DEFINITION = ToolDefinition.builder()
            .name("find_orders").description("Finds orders").inputSchema("{}").build();
    private final DaiPrincipal principal = new DaiPrincipal(UUID.randomUUID(), SubjectType.USER, "local", "alice",
            "Alice", Set.of(), Set.of(), Map.of(), Map.of(), Classification.INTERNAL, null, Set.of());
    private final List<ToolCallRecorder.ToolCall> recorded = new ArrayList<>();
    private final AtomicInteger delegateCalls = new AtomicInteger();

    private ToolCallback delegate(Function<String, String> body) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return DEFINITION;
            }

            @Override
            public String call(String toolInput) {
                delegateCalls.incrementAndGet();
                return body.apply(toolInput);
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                return call(toolInput);
            }
        };
    }

    private ToolBinding binding(WriteMode mode, int maxChars) {
        return new ToolBinding(UUID.randomUUID(), 1, UUID.randomUUID(), "find_orders",
                new ToolSource.OperationSource(
                        CatalogElementRef.operation("com.acme.OrderService", "find", List.of("java.lang.String"))),
                null, Map.of(), mode, false, Duration.ofSeconds(5), 3, new ResultPolicy(maxChars, true), false);
    }

    private SecuredToolCallback callback(ToolBinding binding, ToolCallback delegate, boolean permitted,
                                         ToolCallRecorder recorder, ToolCallScope scope) {
        return new SecuredToolCallback(delegate, binding, principal, new TestingAuthenticationToken("alice", "x"),
                new AtomicInteger(0), (p, b) -> permitted, request -> UUID.fromString(
                "00000000-0000-7000-8000-000000000001"), recorder, scope, Clock.systemUTC());
    }

    private SecuredToolCallback callback(ToolBinding binding, ToolCallback delegate, boolean permitted) {
        return callback(binding, delegate, permitted, recorded::add, ToolCallScope.ofTurn(Channel.CHAT, TURN));
    }

    @Test
    void aSuccessfulCallIsRecordedWithHashesOnly() {
        ToolBinding binding = binding(WriteMode.EXECUTE, 0);
        String result = callback(binding, delegate(in -> "{\"rows\":[1]}"), true).call("{\"q\":\"secret\"}");

        assertThat(result).isEqualTo("{\"rows\":[1]}");
        assertThat(recorded).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo(ToolResultStatus.OK);
            assertThat(c.scope().turnId()).isEqualTo(TURN);
            assertThat(c.scope().channel()).isEqualTo(Channel.CHAT);
            assertThat(c.binding()).isEqualTo(binding);
            assertThat(c.principalId()).isEqualTo(principal.principalId());
            assertThat(c.elementRef().kind()).isEqualTo(CatalogElementRef.Kind.OP);
            assertThat(c.argsSha256()).isEqualTo(Sha256.of("{\"q\":\"secret\"}"));
            assertThat(c.resultSha256()).isEqualTo(Sha256.of("{\"rows\":[1]}"));
            assertThat(c.errorCode()).isNull();
            assertThat(c.writeViolation()).isFalse();
            assertThat(c.endedAt()).isAfterOrEqualTo(c.startedAt());
        });
    }

    @Test
    void theToolRunsInTheAiReadScope() {
        AtomicInteger inScope = new AtomicInteger();
        callback(binding(WriteMode.EXECUTE, 0), delegate(in -> {
            if (com.springaimcpservercommon.ai.guard.AiReadScope.isActive()) {
                inScope.incrementAndGet();
            }
            return "ok";
        }), true).call("{}");

        assertThat(inScope).hasValue(1);
        assertThat(com.springaimcpservercommon.ai.guard.AiReadScope.isActive()).isFalse();
    }

    @Test
    void aDeniedCallIsRecordedAndNeverReachesTheTool() {
        callback(binding(WriteMode.EXECUTE, 0), delegate(in -> "x"), false).call("{}");

        assertThat(delegateCalls).hasValue(0);
        assertThat(recorded).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo(ToolResultStatus.NOT_PERMITTED);
            assertThat(c.errorCode()).isEqualTo("not_permitted");
            assertThat(c.resultSha256()).isNull();
        });
    }

    @Test
    void aProposeToolRecordsTheProposalAndNeverRunsTheTool() {
        callback(binding(WriteMode.PROPOSE, 0), delegate(in -> "x"), true).call("{}");

        assertThat(delegateCalls).hasValue(0);
        assertThat(recorded).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo(ToolResultStatus.PROPOSED);
            assertThat(c.proposalId()).isEqualTo(UUID.fromString("00000000-0000-7000-8000-000000000001"));
        });
    }

    @Test
    void aVetoedWriteIsFlagged() {
        ToolCallback failing = delegate(in -> {
            throw new AiWriteViolationException("Order", "UPDATE");
        });
        String result = callback(binding(WriteMode.EXECUTE, 0), failing, true).call("{}");

        assertThat(result).contains("internal_error");
        assertThat(recorded).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo(ToolResultStatus.ERROR);
            assertThat(c.errorCode()).isEqualTo("write_violation");
            assertThat(c.writeViolation()).isTrue();
        });
    }

    @Test
    void anOversizedResultIsRecordedAsTruncated() {
        callback(binding(WriteMode.EXECUTE, 5), delegate(in -> "0123456789"), true).call("{}");

        assertThat(recorded).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo(ToolResultStatus.TRUNCATED);
            assertThat(c.truncated()).isTrue();
            assertThat(c.errorCode()).isEqualTo("result_truncated");
        });
    }

    @Test
    void aConstraintViolationIsRecordedAndNeverReachesTheTool() {
        ToolBinding constrained = new ToolBinding(UUID.randomUUID(), 1, UUID.randomUUID(), "find_orders",
                new ToolSource.QuerySource(UUID.randomUUID()), null,
                Map.of("limit", ArgConstraint.range(1, 5)), WriteMode.EXECUTE, false, Duration.ofSeconds(5), 3,
                ResultPolicy.DEFAULT, false);
        callback(constrained, delegate(in -> "ok"), true).call("{\"limit\":99}");

        assertThat(delegateCalls).hasValue(0);
        assertThat(recorded).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo(ToolResultStatus.ERROR);
            assertThat(c.errorCode()).isEqualTo("out_of_range");
        });
    }

    @Test
    void aProposalServiceFailureBecomesAnErrorNotAFakeProposal() {
        SecuredToolCallback cb = new SecuredToolCallback(delegate(in -> "x"), binding(WriteMode.PROPOSE, 0), principal,
                new TestingAuthenticationToken("alice", "x"), new AtomicInteger(0), (p, b) -> true,
                request -> {
                    throw new IllegalStateException("no store");
                }, recorded::add, ToolCallScope.ofTurn(Channel.CHAT, TURN), Clock.systemUTC());
        String result = cb.call("{}");

        assertThat(result).contains("proposal_unavailable").doesNotContain("proposed");
        assertThat(recorded).singleElement().extracting(ToolCallRecorder.ToolCall::status)
                .isEqualTo(ToolResultStatus.ERROR);
    }

    @Test
    void theCallLimitIsRecordedAsAnError() {
        SecuredToolCallback cb = callback(binding(WriteMode.EXECUTE, 0), delegate(in -> "ok"), true);
        for (int i = 0; i < 4; i++) {
            cb.call("{}");
        }

        assertThat(recorded).hasSize(4);
        assertThat(recorded.get(3).status()).isEqualTo(ToolResultStatus.ERROR);
        assertThat(recorded.get(3).errorCode()).isEqualTo("call_limit_exceeded");
    }

    @Test
    void withoutAScopeNothingIsRecorded() {
        callback(binding(WriteMode.EXECUTE, 0), delegate(in -> "ok"), true, recorded::add, null).call("{}");

        assertThat(recorded).isEmpty();
    }

    @Test
    void aFailingRecorderNeverChangesTheResult() {
        ToolCallRecorder broken = call -> {
            throw new IllegalStateException("store down");
        };
        String result = callback(binding(WriteMode.EXECUTE, 0), delegate(in -> "ok"), true, broken,
                ToolCallScope.ofTurn(Channel.CHAT, TURN)).call("{}");

        assertThat(result).isEqualTo("ok");
    }

    @Test
    void everySourceKindMapsToAStoreAcceptedElementRef() {
        UUID id = UUID.randomUUID();
        assertThat(SecuredToolCallback.elementRef(new ToolSource.QuerySource(id)).toString())
                .isEqualTo("query:" + id);
        assertThat(SecuredToolCallback.elementRef(new ToolSource.AgentSource(id)).toString())
                .isEqualTo("agent:" + id);
        assertThat(SecuredToolCallback.elementRef(new ToolSource.McpSource(id, "list files!")).toString())
                .isEqualTo("mcp:" + id + "/list_files_");
    }

    private static ObservationRegistry tracing(List<Observation.Context> stopped) {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                stopped.add(context);
            }
        });
        return registry;
    }

    @Test
    void everyCallIsATraceableSpanWithoutArgumentsOrResults() {
        List<Observation.Context> stopped = new ArrayList<>();
        ObservationRegistry registry = tracing(stopped);
        Observation parent = Observation.start("turn", registry);
        UUID modelCall = UUID.randomUUID();
        SecuredToolCallback cb = new SecuredToolCallback(delegate(in -> "{\"secret-result\":1}"),
                binding(WriteMode.EXECUTE, 0), principal, new TestingAuthenticationToken("alice", "x"),
                new AtomicInteger(0), (p, b) -> true, request -> UUID.randomUUID(), recorded::add,
                ToolCallScope.ofTurn(Channel.CHAT, TURN, modelCall, parent), Clock.systemUTC(), registry);

        cb.call("{\"q\":\"secret-arg\"}");

        assertThat(stopped).singleElement().satisfies(c -> {
            assertThat(c.getName()).isEqualTo("dynamic.ai.agent.tool");
            assertThat(c.getContextualName()).isEqualTo("dai.tool");
            assertThat(c.getLowCardinalityKeyValue("dai.tool.name").getValue()).isEqualTo("find_orders");
            assertThat(c.getLowCardinalityKeyValue("dai.tool.access_mode").getValue()).isEqualTo("READ");
            assertThat(c.getLowCardinalityKeyValue("dai.channel").getValue()).isEqualTo("CHAT");
            assertThat(c.getLowCardinalityKeyValue("dai.tool.status").getValue()).isEqualTo("OK");
            assertThat(c.getHighCardinalityKeyValue("dai.turn.id").getValue()).isEqualTo(TURN.toString());
            assertThat(c.getHighCardinalityKeyValue("dai.model_call.id").getValue()).isEqualTo(modelCall.toString());
            assertThat(c.getParentObservation()).isNotNull();
            assertThat(c.getLowCardinalityKeyValues().toString()).doesNotContain("secret");
            assertThat(c.getHighCardinalityKeyValues().toString()).doesNotContain("secret");
        });
    }

    @Test
    void aDeniedCallShowsItsStatusOnTheSpan() {
        List<Observation.Context> stopped = new ArrayList<>();
        SecuredToolCallback cb = new SecuredToolCallback(delegate(in -> "x"), binding(WriteMode.EXECUTE, 0),
                principal, new TestingAuthenticationToken("alice", "x"), new AtomicInteger(0), (p, b) -> false,
                request -> UUID.randomUUID(), recorded::add, ToolCallScope.ofTurn(Channel.CHAT, TURN),
                Clock.systemUTC(), tracing(stopped));

        cb.call("{}");

        assertThat(stopped).singleElement().satisfies(c -> {
            assertThat(c.getLowCardinalityKeyValue("dai.tool.status").getValue()).isEqualTo("NOT_PERMITTED");
            assertThat(c.getLowCardinalityKeyValue("dai.tool.error_code").getValue()).isEqualTo("not_permitted");
        });
    }

    @Test
    void theProposalServiceGetsTheEffectiveArgumentsTheScopeAndTheInvocationId() {
        List<ProposalService.ProposalRequest> requests = new ArrayList<>();
        ToolBinding constrained = new ToolBinding(UUID.randomUUID(), 1, UUID.randomUUID(), "find_orders",
                new ToolSource.QuerySource(UUID.randomUUID()), null,
                Map.of("status", ArgConstraint.literal("OPEN")), WriteMode.PROPOSE, false, Duration.ofSeconds(5), 3,
                ResultPolicy.DEFAULT, false);
        UUID proposal = UUID.randomUUID();
        UUID modelCall = UUID.randomUUID();
        SecuredToolCallback cb = new SecuredToolCallback(delegate(in -> "x"), constrained, principal,
                new TestingAuthenticationToken("alice", "x"), new AtomicInteger(0), (p, b) -> true, request -> {
                    requests.add(request);
                    return proposal;
                }, recorded::add, ToolCallScope.ofTurn(Channel.CHAT, TURN, modelCall), Clock.systemUTC());

        String result = cb.call("{\"status\":\"CLOSED\",\"note\":\"x\"}");

        assertThat(result).contains(proposal.toString());
        assertThat(requests).singleElement().satisfies(r -> {
            assertThat(r.binding()).isEqualTo(constrained);
            assertThat(r.toolInput()).isEqualTo("{\"note\":\"x\",\"status\":\"OPEN\"}");
            assertThat(r.principal()).isEqualTo(principal);
            assertThat(r.scope().turnId()).isEqualTo(TURN);
            assertThat(r.scope().modelCallId()).isEqualTo(modelCall);
        });
        assertThat(recorded).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo(ToolResultStatus.PROPOSED);
            assertThat(c.proposalId()).isEqualTo(proposal);
            assertThat(c.id()).isEqualTo(requests.getFirst().toolInvocationId());
        });
    }

    @Test
    void aRefusalCarriesItsCodeAndMessageToTheModel() {
        SecuredToolCallback cb = new SecuredToolCallback(delegate(in -> "x"), binding(WriteMode.PROPOSE, 0), principal,
                new TestingAuthenticationToken("alice", "x"), new AtomicInteger(0), (p, b) -> true, request -> {
                    throw new ProposalService.ProposalRefusedException("writes_disabled", "Writes are switched off.");
                }, recorded::add, ToolCallScope.ofTurn(Channel.CHAT, TURN), Clock.systemUTC());

        String result = cb.call("{}");

        assertThat(result).contains("writes_disabled").contains("Writes are switched off.");
        assertThat(recorded).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo(ToolResultStatus.ERROR);
            assertThat(c.errorCode()).isEqualTo("writes_disabled");
        });
    }

    @Test
    void aProposingToolWithoutAScopeIsRefusedNotFaked() {
        SecuredToolCallback cb = new SecuredToolCallback(delegate(in -> "x"), binding(WriteMode.PROPOSE, 0), principal,
                new TestingAuthenticationToken("alice", "x"), new AtomicInteger(0), (p, b) -> true,
                request -> UUID.randomUUID(), recorded::add, null, Clock.systemUTC());

        assertThat(cb.call("{}")).contains("proposal_unavailable");
    }
}
