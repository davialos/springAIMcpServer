package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.autoconfigure.TraceAdminController.TraceNodeDto;
import com.springaimcpservercommon.autoconfigure.TraceAdminController.TraceTreeDto;
import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.hash.Sha256;
import com.springaimcpservercommon.core.id.Ids;
import com.springaimcpservercommon.core.invocation.Channel;
import com.springaimcpservercommon.persistence.telemetry.AgentTurn;
import com.springaimcpservercommon.persistence.telemetry.McpRequest;
import com.springaimcpservercommon.persistence.telemetry.McpRequestStatus;
import com.springaimcpservercommon.persistence.telemetry.ModelCall;
import com.springaimcpservercommon.persistence.telemetry.ModelCallOutcome;
import com.springaimcpservercommon.persistence.telemetry.ModelCallPurpose;
import com.springaimcpservercommon.persistence.telemetry.NewAgentTurn;
import com.springaimcpservercommon.persistence.telemetry.NewMcpRequest;
import com.springaimcpservercommon.persistence.telemetry.NewModelCall;
import com.springaimcpservercommon.persistence.telemetry.NewToolInvocation;
import com.springaimcpservercommon.persistence.telemetry.ToolAccessMode;
import com.springaimcpservercommon.persistence.telemetry.ToolInvocation;
import com.springaimcpservercommon.persistence.telemetry.ToolInvocationStatus;
import com.springaimcpservercommon.persistence.telemetry.TurnFinishReason;
import com.springaimcpservercommon.persistence.telemetry.TurnOutcome;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceTreeBuilderTest {

    private static final Instant T0 = Instant.parse("2026-09-29T10:00:00Z");
    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final UUID PRINCIPAL = UUID.randomUUID();
    private static final CatalogElementRef REF = CatalogElementRef.parse("op:com.acme.Svc#find(java.lang.Long)");

    private static AgentTurn turn(UUID id, TurnOutcome outcome, String error) {
        return AgentTurn.of(new NewAgentTurn(id, T0, T0.plusMillis(2000), null, WORKSPACE, null, null, PRINCIPAL,
                Channel.CHAT, "trace-1", null, TurnFinishReason.STOP, outcome, error, 300));
    }

    private static ModelCall call(UUID turnId, long startMs, long endMs, int in, int out, long cost, String currency) {
        return ModelCall.of(new NewModelCall(Ids.newId(), T0.plusMillis(startMs), T0.plusMillis(endMs), turnId,
                (short) 0, WORKSPACE, ModelCallPurpose.AGENT_TURN, "openai", "gpt-x", true, 100, in, out, 0, cost,
                currency, "stop", ModelCallOutcome.SUCCESS, null, null, null));
    }

    private static ToolInvocation tool(UUID turnId, UUID modelCallId, String name, long startMs, long endMs,
                                       ToolInvocationStatus status, boolean violation) {
        return ToolInvocation.of(new NewToolInvocation(Ids.newId(), T0.plusMillis(startMs), T0.plusMillis(endMs),
                Channel.CHAT, turnId, modelCallId, null, null, WORKSPACE, PRINCIPAL, name, REF, null,
                ToolAccessMode.READ, Sha256.of(name), null, status, null, null, false,
                status == ToolInvocationStatus.ERROR ? "execution_error" : null, violation, null));
    }

    @Test
    void toolCallsSitUnderTheModelCallThatAskedForThemAndOthersUnderTheTurn() {
        UUID turnId = Ids.newId();
        ModelCall model = call(turnId, 10, 1900, 1200, 80, 2_500, "EUR");
        ToolInvocation asked = tool(turnId, model.getId(), "find_orders", 400, 700, ToolInvocationStatus.OK, false);
        ToolInvocation unattributed = tool(turnId, null, "get_customer", 200, 300, ToolInvocationStatus.OK, false);
        ToolInvocation orphan = tool(turnId, UUID.randomUUID(), "list_items", 800, 900, ToolInvocationStatus.OK, false);

        TraceTreeDto tree = TraceTreeBuilder.turn(turn(turnId, TurnOutcome.SUCCESS, null), List.of(model),
                List.of(asked, unattributed, orphan));

        assertThat(tree.root().kind()).isEqualTo(TraceTreeBuilder.TURN);
        assertThat(tree.root().status()).isEqualTo("SUCCESS");
        assertThat(tree.root().durationMs()).isEqualTo(2000);
        // root children ordered by start: get_customer (200), model call (10) first -> model, get_customer, list_items
        assertThat(tree.root().children()).extracting(TraceNodeDto::kind, TraceNodeDto::name)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(TraceTreeBuilder.MODEL_CALL, "openai/gpt-x"),
                        org.assertj.core.groups.Tuple.tuple(TraceTreeBuilder.TOOL_CALL, "get_customer"),
                        org.assertj.core.groups.Tuple.tuple(TraceTreeBuilder.TOOL_CALL, "list_items"));
        TraceNodeDto modelNode = tree.root().children().getFirst();
        assertThat(modelNode.offsetMs()).isEqualTo(10);
        assertThat(modelNode.children()).singleElement().satisfies(t -> {
            assertThat(t.name()).isEqualTo("find_orders");
            assertThat(t.offsetMs()).isEqualTo(400);
            assertThat(t.durationMs()).isEqualTo(300);
        });
        assertThat(modelNode.attributes()).containsEntry("inputTokens", 1200).containsEntry("costMicros", 2_500L)
                .containsEntry("provider", "openai").doesNotContainKey("fallbackOfId");
    }

    @Test
    void theSummaryCountsProblemsDenialsVetoesTokensAndCost() {
        UUID turnId = Ids.newId();
        ModelCall a = call(turnId, 0, 500, 100, 10, 1_000, "EUR");
        ModelCall b = call(turnId, 600, 900, 200, 20, 500, "EUR");
        List<ToolInvocation> tools = List.of(
                tool(turnId, a.getId(), "find_orders", 10, 20, ToolInvocationStatus.OK, false),
                tool(turnId, a.getId(), "find_orders", 30, 40, ToolInvocationStatus.ERROR, true),
                tool(turnId, b.getId(), "secret_tool", 50, 60, ToolInvocationStatus.NOT_PERMITTED, false));

        var summary = TraceTreeBuilder.turn(turn(turnId, TurnOutcome.SUCCESS, null), List.of(a, b), tools).summary();

        assertThat(summary.modelCalls()).isEqualTo(2);
        assertThat(summary.toolCalls()).isEqualTo(3);
        assertThat(summary.toolProblems()).isEqualTo(1);
        assertThat(summary.notPermitted()).isEqualTo(1);
        assertThat(summary.writeViolations()).isEqualTo(1);
        assertThat(summary.inputTokens()).isEqualTo(300);
        assertThat(summary.outputTokens()).isEqualTo(30);
        assertThat(summary.costMicros()).isEqualTo(1_500);
        assertThat(summary.currency()).isEqualTo("EUR");
        assertThat(summary.mixedCurrency()).isFalse();
    }

    @Test
    void differentCurrenciesAreFlaggedInsteadOfBeingSummedAsOne() {
        UUID turnId = Ids.newId();
        var summary = TraceTreeBuilder.turn(turn(turnId, TurnOutcome.SUCCESS, null),
                List.of(call(turnId, 0, 10, 1, 1, 100, "EUR"), call(turnId, 20, 30, 1, 1, 100, "USD")), List.of())
                .summary();

        assertThat(summary.mixedCurrency()).isTrue();
        assertThat(summary.currency()).isNull();
    }

    @Test
    void aFailedTurnKeepsItsErrorCodeAndAnEmptyTurnHasNoChildren() {
        TraceTreeDto tree = TraceTreeBuilder.turn(turn(Ids.newId(), TurnOutcome.FAILED, "model-error"), List.of(),
                List.of());

        assertThat(tree.root().status()).isEqualTo("FAILED");
        assertThat(tree.root().errorCode()).isEqualTo("model-error");
        assertThat(tree.root().children()).isEmpty();
        assertThat(tree.summary().costMicros()).isZero();
    }

    @Test
    void anMcpRequestTreeHoldsItsToolCalls() {
        UUID requestId = Ids.newId();
        McpRequest request = McpRequest.of(new NewMcpRequest(requestId, T0, T0.plusMillis(90), null, null, PRINCIPAL,
                WORKSPACE, "tools/call", "7", "find_orders", McpRequestStatus.OK, null, null));
        ToolInvocation call = ToolInvocation.of(new NewToolInvocation(Ids.newId(), T0.plusMillis(10),
                T0.plusMillis(60), Channel.MCP, null, null, null, requestId, WORKSPACE, PRINCIPAL, "find_orders", REF,
                null, ToolAccessMode.READ, Sha256.of("x"), null, ToolInvocationStatus.OK, null, null, false, null,
                false, null));

        TraceTreeDto tree = TraceTreeBuilder.mcpRequest(request, List.of(call));

        assertThat(tree.root().kind()).isEqualTo(TraceTreeBuilder.MCP_REQUEST);
        assertThat(tree.root().name()).isEqualTo("tools/call find_orders");
        assertThat(tree.root().durationMs()).isEqualTo(90);
        assertThat(tree.root().children()).singleElement().satisfies(n -> {
            assertThat(n.offsetMs()).isEqualTo(10);
            assertThat(n.durationMs()).isEqualTo(50);
        });
        assertThat(tree.summary().toolCalls()).isEqualTo(1);
    }

    @Test
    void queryParametersAreValidatedWithoutEchoingTheirValues() {
        assertThat(TraceAdminController.enumParam(TurnOutcome.class, "failed", "outcome")).isEqualTo(TurnOutcome.FAILED);
        assertThat(TraceAdminController.enumParam(TurnOutcome.class, " ", "outcome")).isNull();
        assertThatThrownBy(() -> TraceAdminController.enumParam(TurnOutcome.class, "secret-value", "outcome"))
                .hasMessageContaining("outcome must be one of").hasMessageNotContaining("secret-value");
        assertThat(TraceAdminController.matching(java.util.regex.Pattern.compile("^[a-z]+$"), "abc", "x"))
                .isEqualTo("abc");
        assertThatThrownBy(() -> TraceAdminController.matching(java.util.regex.Pattern.compile("^[a-z]+$"),
                "DROP TABLE", "toolName")).hasMessage("toolName has an invalid format");
    }
}
