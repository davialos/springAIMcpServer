package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.mcp.server.McpRequestRecorder;
import com.springaimcpservercommon.persistence.telemetry.McpRequestStatus;
import com.springaimcpservercommon.persistence.telemetry.NewMcpRequest;
import com.springaimcpservercommon.persistence.telemetry.TelemetryStore;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Writes MCP requests to {@code dai_mcp_request} (F-72, LLD-15): method, tool name, outcome, caller, workspace and
 * client — never arguments or results. Off the request path under a bulkhead of {@value #MAX_IN_FLIGHT} concurrent
 * writes, like {@link StoreTurnRecorder}; a full bulkhead or a failed write drops the record and counts it
 * ({@code dynamic.ai.agent.mcp.record.dropped}, {@code ...failures}).
 */
@NullMarked
final class StoreMcpRequestRecorder implements McpRequestRecorder, AutoCloseable {

    static final int MAX_IN_FLIGHT = 64;

    private static final Logger LOG = LoggerFactory.getLogger(StoreMcpRequestRecorder.class);

    private final TelemetryStore store;
    private final BoundedAsyncWriter writer = new BoundedAsyncWriter(MAX_IN_FLIGHT, "dynamic.ai.agent.mcp.record", LOG);

    StoreMcpRequestRecorder(TelemetryStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public void record(McpCall call) {
        writer.submit("Recording MCP request " + call.id(), () -> {
            store.recordMcpRequest(toRow(call));
            SafeMetrics.count("dynamic.ai.agent.mcp.requests", "method", call.method(), "status",
                    call.status().name());
        });
    }

    static NewMcpRequest toRow(McpCall c) {
        return new NewMcpRequest(c.id(), c.receivedAt(), c.completedAt(), null, c.mcpClientId(), c.principalId(),
                c.workspaceId(), c.method(), c.jsonrpcId(), c.toolName(),
                McpRequestStatus.valueOf(c.status().name()), c.errorCode(), c.traceId());
    }

    @Override
    public void close() {
        writer.close();
    }
}
