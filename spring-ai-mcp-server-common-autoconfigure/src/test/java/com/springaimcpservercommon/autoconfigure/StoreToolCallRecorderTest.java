package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.tool.ResultPolicy;
import com.springaimcpservercommon.ai.tool.ToolBinding;
import com.springaimcpservercommon.ai.tool.ToolCallRecorder.ToolCall;
import com.springaimcpservercommon.ai.tool.ToolCallScope;
import com.springaimcpservercommon.ai.tool.ToolResultStatus;
import com.springaimcpservercommon.ai.tool.ToolSource;
import com.springaimcpservercommon.ai.tool.WriteMode;
import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.hash.Sha256;
import com.springaimcpservercommon.core.invocation.Channel;
import com.springaimcpservercommon.persistence.telemetry.NewToolInvocation;
import com.springaimcpservercommon.persistence.telemetry.ToolAccessMode;
import com.springaimcpservercommon.persistence.telemetry.ToolInvocation;
import com.springaimcpservercommon.persistence.telemetry.ToolInvocationStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StoreToolCallRecorderTest {

    private static final Instant T0 = Instant.parse("2026-09-29T10:00:00Z");

    private static ToolBinding binding(WriteMode mode) {
        return new ToolBinding(UUID.randomUUID(), 1, UUID.randomUUID(), "find_orders",
                new ToolSource.OperationSource(
                        CatalogElementRef.operation("com.acme.OrderService", "find", List.of("java.lang.String"))),
                null, Map.of(), mode, false, Duration.ofSeconds(5), 3, ResultPolicy.DEFAULT, false);
    }

    private static ToolCall call(WriteMode mode, ToolCallScope scope, ToolResultStatus status,
                                 UUID proposalId) {
        ToolBinding b = binding(mode);
        return new ToolCall(UUID.randomUUID(), T0, T0.plusMillis(20), scope, b, UUID.randomUUID(),
                CatalogElementRef.operation("com.acme.OrderService", "find", List.of("java.lang.String")),
                Sha256.of("{}"), status, Sha256.of("r"), false, null, false, proposalId);
    }

    @Test
    void theRowPassesTheEntityValidationForEachChannelAndStatus() {
        UUID turn = UUID.randomUUID();
        UUID mcp = UUID.randomUUID();
        List<ToolCall> calls = List.of(
                call(WriteMode.EXECUTE, ToolCallScope.ofTurn(Channel.CHAT, turn), ToolResultStatus.OK, null),
                call(WriteMode.EXECUTE, ToolCallScope.ofTurn(Channel.PLAYGROUND, turn), ToolResultStatus.EMPTY, null),
                call(WriteMode.EXECUTE, ToolCallScope.ofMcpRequest(mcp), ToolResultStatus.NOT_PERMITTED, null),
                call(WriteMode.PROPOSE, ToolCallScope.ofTurn(Channel.CHAT, turn), ToolResultStatus.PROPOSED,
                        UUID.randomUUID()));
        for (ToolCall c : calls) {
            NewToolInvocation row = StoreToolCallRecorder.toRow(c);
            assertThat(ToolInvocation.of(row).getId()).isEqualTo(c.id());
            assertThat(row.status()).isEqualTo(ToolInvocationStatus.valueOf(c.status().name()));
        }
    }

    @Test
    void theAccessModeFollowsTheBindingsWriteMode() {
        UUID turn = UUID.randomUUID();
        assertThat(StoreToolCallRecorder.toRow(call(WriteMode.EXECUTE, ToolCallScope.ofTurn(Channel.CHAT, turn),
                ToolResultStatus.OK, null)).accessMode()).isEqualTo(ToolAccessMode.READ);
        assertThat(StoreToolCallRecorder.toRow(call(WriteMode.PROPOSE, ToolCallScope.ofTurn(Channel.CHAT, turn),
                ToolResultStatus.PROPOSED, UUID.randomUUID())).accessMode()).isEqualTo(ToolAccessMode.PROPOSE);
    }

    @Test
    void callsWithoutTheOriginTheirChannelNeedsAreNotAttributable() {
        assertThat(StoreToolCallRecorder.attributable(call(WriteMode.EXECUTE,
                new ToolCallScope(Channel.CHAT, null, null), ToolResultStatus.OK, null))).isFalse();
        assertThat(StoreToolCallRecorder.attributable(call(WriteMode.EXECUTE,
                new ToolCallScope(Channel.MCP, UUID.randomUUID(), null), ToolResultStatus.OK, null))).isFalse();
        assertThat(StoreToolCallRecorder.attributable(call(WriteMode.EXECUTE,
                new ToolCallScope(Channel.ENDPOINT, null, null), ToolResultStatus.OK, null))).isTrue();
    }
}
