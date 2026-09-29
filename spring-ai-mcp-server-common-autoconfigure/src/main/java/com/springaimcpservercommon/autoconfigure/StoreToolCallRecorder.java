package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.tool.ToolCallRecorder;
import com.springaimcpservercommon.ai.tool.WriteMode;
import com.springaimcpservercommon.core.invocation.Channel;
import com.springaimcpservercommon.persistence.telemetry.NewToolInvocation;
import com.springaimcpservercommon.persistence.telemetry.TelemetryStore;
import com.springaimcpservercommon.persistence.telemetry.ToolAccessMode;
import com.springaimcpservercommon.persistence.telemetry.ToolInvocationStatus;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Writes tool calls to {@code dai_tool_invocation} (F-72, LLD-15): who ran which tool as whom, with what outcome,
 * hashes of arguments and result, write-guard vetoes and the proposal a PROPOSE tool created. Arguments and results
 * are never stored (only their hashes), so no prompt or row data reaches the store.
 *
 * <p>Off the request path, like {@link StoreTurnRecorder}: each call is written on a virtual thread under a
 * bulkhead of {@value #MAX_IN_FLIGHT} concurrent writes; a full bulkhead or a failed write drops the record and
 * counts it ({@code dynamic.ai.agent.tool.record.dropped}, {@code ...failures}). A call whose channel has no
 * origin (a chat call without a turn, an MCP call without a request) cannot satisfy the table's origin check and
 * is counted as {@code dynamic.ai.agent.tool.record.unattributed}.
 *
 * <p>{@code binding_revision_id} is left empty: a tool binding's revision is not yet resolved to the revision row
 * id at call time (OQ-43).
 */
@NullMarked
final class StoreToolCallRecorder implements ToolCallRecorder, AutoCloseable {

    static final int MAX_IN_FLIGHT = 64;

    private static final Logger LOG = LoggerFactory.getLogger(StoreToolCallRecorder.class);

    private final TelemetryStore store;
    private final BoundedAsyncWriter writer =
            new BoundedAsyncWriter(MAX_IN_FLIGHT, "dynamic.ai.agent.tool.record", LOG);

    StoreToolCallRecorder(TelemetryStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public void record(ToolCall call) {
        if (!attributable(call)) {
            SafeMetrics.count("dynamic.ai.agent.tool.record.unattributed");
            return;
        }
        writer.submit("Recording tool call " + call.id(), () -> {
            store.recordToolInvocation(toRow(call));
            SafeMetrics.count("dynamic.ai.agent.tool.calls", "status", call.status().name());
        });
    }

    /** Whether the call has the origin its channel requires ({@code ck_tool_invocation_origin}). */
    static boolean attributable(ToolCall call) {
        Channel channel = call.scope().channel();
        return switch (channel) {
            case CHAT, PLAYGROUND -> call.scope().turnId() != null;
            case MCP -> call.scope().mcpRequestId() != null;
            case ENDPOINT -> true;
        };
    }

    static NewToolInvocation toRow(ToolCall c) {
        ToolAccessMode mode = c.binding().writeMode() == WriteMode.PROPOSE ? ToolAccessMode.PROPOSE
                : ToolAccessMode.READ;
        return new NewToolInvocation(c.id(), c.startedAt(), c.endedAt(), c.scope().channel(), c.scope().turnId(),
                c.scope().modelCallId(), null, c.scope().mcpRequestId(), c.binding().workspaceId(), c.principalId(),
                c.binding().toolName(), c.elementRef(), null, mode, c.argsSha256(), null,
                ToolInvocationStatus.valueOf(c.status().name()), null, c.resultSha256(), c.truncated(),
                c.errorCode(), c.writeViolation(), c.proposalId());
    }

    @Override
    public void close() {
        writer.close();
    }
}
