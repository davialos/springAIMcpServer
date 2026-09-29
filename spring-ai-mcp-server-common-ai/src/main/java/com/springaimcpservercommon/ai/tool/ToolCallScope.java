package com.springaimcpservercommon.ai.tool;

import com.springaimcpservercommon.core.invocation.Channel;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Where a set of tool callbacks is used: the entry channel and the turn or MCP request they belong to. It is fixed
 * when the callbacks are built for one turn or request, so recording never depends on thread-local or scoped state
 * that Reactor may not propagate to the thread that runs a tool.
 *
 * @param channel      entry channel
 * @param turnId       agent turn, for CHAT and PLAYGROUND
 * @param mcpRequestId MCP request, for MCP
 */
public record ToolCallScope(Channel channel, @Nullable UUID turnId, @Nullable UUID mcpRequestId) {

    /** Validates the components. */
    public ToolCallScope {
        Objects.requireNonNull(channel, "channel");
    }

    /**
     * Scope of a tool loop inside an agent turn.
     *
     * @param channel entry channel of the turn
     * @param turnId  turn id
     * @return the scope
     */
    public static ToolCallScope ofTurn(Channel channel, UUID turnId) {
        return new ToolCallScope(channel, Objects.requireNonNull(turnId, "turnId"), null);
    }

    /**
     * Scope of a tool call made directly through MCP.
     *
     * @param mcpRequestId MCP request id
     * @return the scope
     */
    public static ToolCallScope ofMcpRequest(UUID mcpRequestId) {
        return new ToolCallScope(Channel.MCP, null, Objects.requireNonNull(mcpRequestId, "mcpRequestId"));
    }
}
