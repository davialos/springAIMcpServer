package com.springaimcpservercommon.persistence.telemetry;

import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.hash.Sha256;
import com.springaimcpservercommon.core.id.Ids;
import com.springaimcpservercommon.core.invocation.Channel;
import com.springaimcpservercommon.persistence.support.PageRequest;
import com.springaimcpservercommon.persistence.support.TimeRange;
import com.springaimcpservercommon.persistence.unit.DaiPersistenceUnit;
import com.springaimcpservercommon.persistence.unit.PostgresTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Telemetry store against PostgreSQL: partition routing, reads, MCP sessions and chat memory. */
class TelemetryStoreIT {

    private static DaiPersistenceUnit unit;
    private static TelemetryStore telemetry;
    private static UUID workspace;
    private static UUID principal;

    @BeforeAll
    static void start() {
        unit = PostgresTestSupport.startFreshUnit();
        telemetry = new TelemetryStore(unit, Clock.systemUTC());
        workspace = PostgresTestSupport.workspace(unit.schema());
        principal = PostgresTestSupport.principal(unit.schema());
    }

    @AfterAll
    static void stop() {
        unit.close();
    }

    private static String partitionOf(String table, UUID id) {
        return PostgresTestSupport.jdbc().queryForObject(
                "SELECT tableoid::regclass::text FROM " + unit.schema() + "." + table + " WHERE id = ?", String.class, id);
    }

    private static String currentMonthSuffix(Instant at) {
        return DateTimeFormatter.ofPattern("yyyyMM").withZone(ZoneOffset.UTC).format(at);
    }

    @Test
    void turnModelCallAndToolInvocationLandInTheMonthlyPartition() {
        Instant start = Instant.now();
        UUID turnId = Ids.newId();
        telemetry.recordTurn(new NewAgentTurn(turnId, start, start.plusMillis(1500), null, workspace, null, null,
                principal, Channel.CHAT, "trace-1", null, TurnFinishReason.STOP, TurnOutcome.SUCCESS, null, 320));
        UUID callId = Ids.newId();
        telemetry.recordModelCall(new NewModelCall(callId, start.plusMillis(10), start.plusMillis(900), turnId,
                (short) 0, workspace, ModelCallPurpose.AGENT_TURN, "openai", "gpt-x", true, 300, 1200, 80, 0, 2_500L,
                "EUR", "stop", ModelCallOutcome.SUCCESS, null, "req-1", null));
        UUID invocationId = Ids.newId();
        telemetry.recordToolInvocation(new NewToolInvocation(invocationId, start.plusMillis(200),
                start.plusMillis(400), Channel.CHAT, turnId, callId, "call_1", null, workspace, principal,
                "find_orders", CatalogElementRef.parse("op:com.acme.OrderService#find(java.lang.Long)"), null,
                ToolAccessMode.READ, Sha256.of("{\"id\":1}"), "{\"id\": 1}", ToolInvocationStatus.OK, 3,
                Sha256.of("rows"), false, null, false, null));

        String month = currentMonthSuffix(start);
        assertThat(partitionOf("dai_agent_turn", turnId)).endsWith("dai_agent_turn_p" + month);
        assertThat(partitionOf("dai_model_call", callId)).endsWith("dai_model_call_p" + month);
        assertThat(partitionOf("dai_tool_invocation", invocationId)).endsWith("dai_tool_invocation_p" + month);

        TimeRange range = TimeRange.lastUntil(start.plusSeconds(60), Duration.ofHours(1));
        assertThat(telemetry.turnsOfPrincipal(principal, range, PageRequest.first(10)).items())
                .extracting(AgentTurn::getId).contains(turnId);
        assertThat(telemetry.findTurn(turnId, range)).isPresent();
        assertThat(telemetry.modelCallsOfTurn(turnId)).singleElement().satisfies(c -> {
            assertThat(c.getCostMicros()).isEqualTo(2_500L);
            assertThat(c.getCurrency()).isEqualTo("EUR");
        });
        assertThat(telemetry.toolInvocationsOfTurn(turnId)).singleElement()
                .satisfies(i -> assertThat(i.getArgsRedactedJson()).isEqualTo("{\"id\": 1}"));
    }

    @Test
    void mcpSessionLifecycleAndRequests() {
        String hash = Sha256.of("mcp-session-" + UUID.randomUUID());
        McpSession session = telemetry.openMcpSession(new NewMcpSession(hash, null, workspace, principal,
                McpTransport.STREAMABLE_HTTP, "2025-11-25", "inspector", "1.0"));
        assertThat(telemetry.openMcpSession(new NewMcpSession(hash, null, workspace, principal,
                McpTransport.STREAMABLE_HTTP, null, null, null)).getId()).isEqualTo(session.getId());

        UUID requestId = Ids.newId();
        Instant now = Instant.now();
        telemetry.recordMcpRequest(new NewMcpRequest(requestId, now, now.plusMillis(20), session.getId(), null,
                principal, workspace, "tools/call", "7", "find_orders", McpRequestStatus.OK, null, null));
        assertThat(partitionOf("dai_mcp_request", requestId)).endsWith("dai_mcp_request_p" + currentMonthSuffix(now));

        assertThat(telemetry.touchMcpSession(session.getId())).isTrue();
        assertThat(telemetry.endMcpSession(session.getId(), McpSessionEndReason.CLIENT_CLOSED)).isTrue();
        assertThat(telemetry.endMcpSession(session.getId(), McpSessionEndReason.ERROR)).isFalse();
        assertThat(telemetry.touchMcpSession(session.getId())).isFalse();
        assertThat(telemetry.findMcpSession(hash).orElseThrow().getEndReason()).isEqualTo(McpSessionEndReason.CLIENT_CLOSED);
        assertThat(telemetry.mcpRequestsOfSession(session.getId(), TimeRange.lastUntil(now.plusSeconds(60),
                Duration.ofHours(1)), PageRequest.first(10)).items()).hasSize(1);
    }

    @Test
    void traceQueriesFilterAndAggregate() {
        Instant start = Instant.now();
        String trace = "trace-" + UUID.randomUUID();
        UUID okTurn = Ids.newId();
        UUID failedTurn = Ids.newId();
        telemetry.recordTurn(new NewAgentTurn(okTurn, start, start.plusMillis(500), null, workspace, null, null,
                principal, Channel.CHAT, trace, null, TurnFinishReason.STOP, TurnOutcome.SUCCESS, null, null));
        telemetry.recordTurn(new NewAgentTurn(failedTurn, start, start.plusMillis(500), null, workspace, null, null,
                principal, Channel.PLAYGROUND, null, null, TurnFinishReason.ERROR, TurnOutcome.FAILED, "boom", null));
        CatalogElementRef ref = CatalogElementRef.parse("op:com.acme.OrderService#find(java.lang.Long)");
        telemetry.recordToolInvocation(new NewToolInvocation(Ids.newId(), start, start.plusMillis(100), Channel.CHAT,
                okTurn, null, null, null, workspace, principal, "trace_tool_a", ref, null, ToolAccessMode.READ,
                Sha256.of("1"), null, ToolInvocationStatus.OK, 1, Sha256.of("r"), false, null, false, null));
        telemetry.recordToolInvocation(new NewToolInvocation(Ids.newId(), start, start.plusMillis(300), Channel.CHAT,
                okTurn, null, null, null, workspace, principal, "trace_tool_a", ref, null, ToolAccessMode.READ,
                Sha256.of("2"), null, ToolInvocationStatus.OK, 1, Sha256.of("r"), false, null, false, null));
        telemetry.recordToolInvocation(new NewToolInvocation(Ids.newId(), start, start.plusMillis(50),
                Channel.PLAYGROUND, failedTurn, null, null, null, workspace, principal, "trace_tool_b", ref, null,
                ToolAccessMode.READ, Sha256.of("3"), null, ToolInvocationStatus.ERROR, null, null, false,
                "write_violation", true, null));
        telemetry.recordMcpRequest(new NewMcpRequest(Ids.newId(), start, start.plusMillis(5), null, null, principal,
                workspace, "tools/call", "1", "trace_tool_a", McpRequestStatus.OK, null, null));
        telemetry.recordMcpRequest(new NewMcpRequest(Ids.newId(), start, start.plusMillis(5), null, null, principal,
                workspace, "ping", "2", null, McpRequestStatus.ERROR, "internal_error", null));
        UUID tracedRequest = Ids.newId();
        telemetry.recordMcpRequest(new NewMcpRequest(tracedRequest, start, start.plusMillis(5), null, null, principal,
                workspace, "prompts/get", "3", null, McpRequestStatus.OK, null, trace));

        TimeRange range = TimeRange.lastUntil(start.plusSeconds(60), Duration.ofHours(1));
        PageRequest page = PageRequest.first(50);

        assertThat(telemetry.turnsOfWorkspace(workspace, range,
                new TurnFilter(null, null, TurnOutcome.FAILED, null), page).items())
                .extracting(AgentTurn::getId).contains(failedTurn).doesNotContain(okTurn);
        assertThat(telemetry.turnsOfWorkspace(workspace, range,
                new TurnFilter(principal, null, null, Channel.CHAT), page).items())
                .extracting(AgentTurn::getId).contains(okTurn).doesNotContain(failedTurn);
        assertThat(telemetry.turnsOfWorkspace(workspace, range,
                new TurnFilter(UUID.randomUUID(), null, null, null), page).items()).isEmpty();
        assertThat(telemetry.turnsOfTrace(trace, range)).extracting(AgentTurn::getId).containsExactly(okTurn);
        assertThat(telemetry.mcpRequestsOfTrace(trace, range)).extracting(McpRequest::getId)
                .containsExactly(tracedRequest);

        assertThat(telemetry.toolInvocationsOfWorkspace(workspace, range,
                new ToolInvocationFilter("trace_tool_a", null, null, false, false), page).items()).hasSize(2);
        assertThat(telemetry.toolInvocationsOfWorkspace(workspace, range,
                new ToolInvocationFilter(null, ToolInvocationStatus.ERROR, null, false, false), page).items())
                .extracting(ToolInvocation::getToolName).contains("trace_tool_b").doesNotContain("trace_tool_a");
        assertThat(telemetry.toolInvocationsOfWorkspace(workspace, range,
                new ToolInvocationFilter(null, null, null, true, false), page).items())
                .extracting(ToolInvocation::getToolName).contains("trace_tool_b");
        assertThat(telemetry.toolInvocationsOfWorkspace(workspace, range,
                new ToolInvocationFilter("trace_tool_a", null, null, false, true), page).items()).isEmpty();

        List<ToolStat> stats = telemetry.toolStats(workspace, range, 100);
        assertThat(stats).filteredOn(t -> t.toolName().equals("trace_tool_a")).singleElement().satisfies(t -> {
            assertThat(t.calls()).isEqualTo(2);
            assertThat(t.errors()).isZero();
            assertThat(t.avgMillis()).isBetween(199.0, 201.0);
            assertThat(t.maxMillis()).isBetween(299.0, 301.0);
        });
        assertThat(stats).filteredOn(t -> t.toolName().equals("trace_tool_b")).singleElement().satisfies(t -> {
            assertThat(t.errors()).isEqualTo(1);
            assertThat(t.writeViolations()).isEqualTo(1);
        });

        assertThat(telemetry.mcpRequestsOfWorkspace(workspace, range,
                new McpRequestFilter("tools/call", "trace_tool_a", null, null), page).items()).hasSize(1);
        assertThat(telemetry.mcpRequestsOfWorkspace(workspace, range,
                new McpRequestFilter(null, null, McpRequestStatus.ERROR, null), page).items())
                .extracting(McpRequest::getJsonrpcMethod).contains("ping");
    }

    @Test
    void conversationMemoryAppendReplaceAndErase() {
        String key = Sha256.of("conversation-" + UUID.randomUUID());
        NewConversation data = new NewConversation(key, workspace, null, principal, Channel.CHAT, "Orders",
                Duration.ofDays(30));
        Conversation conversation = telemetry.openConversation(data);
        assertThat(telemetry.openConversation(data).getId()).isEqualTo(conversation.getId());

        telemetry.appendMessage(conversation.getId(), new NewMessage(MessageRole.USER, "hi", false, null, null, 1));
        telemetry.appendMessage(conversation.getId(), new NewMessage(MessageRole.ASSISTANT, "hello", false, null, null, 1));
        assertThatThrownBy(() -> telemetry.appendMessage(conversation.getId(),
                new NewMessage(MessageRole.TOOL, "{}", true, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(telemetry.messages(conversation.getId(), 10)).extracting(ConversationMessage::getSeq)
                .containsExactly(0, 1);
        assertThat(telemetry.messages(conversation.getId(), 1)).extracting(ConversationMessage::getContent)
                .containsExactly("hello");

        telemetry.replaceMessages(conversation.getId(), List.of(
                new NewMessage(MessageRole.SYSTEM, "summary", false, null, null, null)));
        assertThat(telemetry.messages(conversation.getId(), 10)).extracting(ConversationMessage::getContent)
                .containsExactly("summary");

        assertThat(telemetry.eraseConversation(conversation.getId())).isTrue();
        assertThat(telemetry.messages(conversation.getId(), 10)).isEmpty();
        Conversation erased = telemetry.findConversation(key).orElseThrow();
        assertThat(erased.getStatus()).isEqualTo(ConversationStatus.ERASED);
        assertThat(erased.getTitle()).isNull();
        assertThatThrownBy(() -> telemetry.appendMessage(conversation.getId(),
                new NewMessage(MessageRole.USER, "again", false, null, null, null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anEraseKeptForAuditHidesTheConversationButKeepsItsMessagesUntilTheHoldEnds() {
        String key = Sha256.of("audit-" + UUID.randomUUID());
        Conversation conversation = telemetry.openConversation(new NewConversation(key, workspace, null, principal,
                Channel.CHAT, "Refund question", Duration.ofDays(30)));
        telemetry.appendMessage(conversation.getId(), new NewMessage(MessageRole.USER, "refund?", false, null, null, 1));

        assertThat(telemetry.eraseConversationKeepingForAudit(conversation.getId(), Duration.ofDays(90))).isTrue();
        assertThat(telemetry.eraseConversationKeepingForAudit(conversation.getId(), Duration.ofDays(90))).isFalse();

        Conversation held = telemetry.findConversation(key).orElseThrow();
        assertThat(held.getStatus()).isEqualTo(ConversationStatus.ERASED);
        assertThat(held.getErasedAt()).isNotNull();
        assertThat(held.getAuditHoldUntil()).isEqualTo(held.getRetentionUntil()).isAfter(held.getErasedAt());
        assertThat(held.getTitle()).isEqualTo("Refund question");
        assertThat(telemetry.messages(conversation.getId(), 10)).extracting(ConversationMessage::getContent)
                .containsExactly("refund?");
        assertThatThrownBy(() -> telemetry.appendMessage(conversation.getId(),
                new NewMessage(MessageRole.USER, "again", false, null, null, null)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(telemetry.conversationsOfWorkspace(workspace, ConversationStatus.ERASED, principal,
                PageRequest.first(500)).items()).extracting(Conversation::getId).contains(conversation.getId());

        assertThat(telemetry.purgeConversation(conversation.getId())).isTrue();
        assertThat(telemetry.findConversation(key)).isEmpty();
        assertThat(telemetry.messages(conversation.getId(), 10)).isEmpty();
        assertThat(telemetry.purgeConversation(conversation.getId())).isFalse();
    }

    @Test
    void theDatabaseRefusesAnAuditHoldOnAConversationThatIsNotErased() {
        Conversation conversation = telemetry.openConversation(new NewConversation(
                Sha256.of("hold-" + UUID.randomUUID()), workspace, null, principal, Channel.CHAT, null,
                Duration.ofDays(1)));
        assertThatThrownBy(() -> PostgresTestSupport.jdbc().update("UPDATE " + unit.schema()
                + ".dai_conversation SET audit_hold_until = now() + interval '1 day' WHERE id = ?",
                conversation.getId()))
                .hasStackTraceContaining("ck_conversation_audit_hold");
    }

    @Test
    void concurrentAppendsKeepSequenceContiguous() throws Exception {
        Conversation conversation = telemetry.openConversation(new NewConversation(
                Sha256.of("conc-" + UUID.randomUUID()), workspace, null, principal, Channel.CHAT, null, Duration.ofDays(1)));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        try {
            List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 30; i++) {
                futures.add(pool.submit(() -> telemetry.appendMessage(conversation.getId(),
                        new NewMessage(MessageRole.USER, "m", false, null, null, null))));
            }
            for (var f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(telemetry.messages(conversation.getId(), 100)).extracting(ConversationMessage::getSeq)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 30).boxed().toList());
    }

    @Test
    void purgeRemovesConversationsPastRetention() {
        String key = Sha256.of("old-" + UUID.randomUUID());
        Conversation old = telemetry.openConversation(new NewConversation(key, workspace, null, principal,
                Channel.CHAT, null, Duration.ofSeconds(1)));
        telemetry.appendMessage(old.getId(), new NewMessage(MessageRole.USER, "bye", false, null, null, null));
        PostgresTestSupport.jdbc().update("UPDATE " + unit.schema()
                + ".dai_conversation SET retention_until = now() - interval '1 day' WHERE id = ?", old.getId());

        assertThat(telemetry.purgeExpiredConversations(100)).isGreaterThanOrEqualTo(1);
        assertThat(telemetry.findConversation(key)).isEmpty();
        assertThat(PostgresTestSupport.jdbc().queryForObject("SELECT count(*) FROM " + unit.schema()
                + ".dai_conversation_message WHERE conversation_id = ?", Integer.class, old.getId())).isZero();
    }
}
