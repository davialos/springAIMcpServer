package com.springaimcpservercommon.ai.tool;

import com.springaimcpservercommon.ai.guard.AiWriteViolationException;
import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.hash.Sha256;
import com.springaimcpservercommon.core.invocation.Channel;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.core.principal.SubjectType;
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
                new AtomicInteger(0), (p, b) -> permitted, (tool, input, bindingId, p) -> UUID.fromString(
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
                (tool, input, bindingId, p) -> {
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
}
