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
 * @param modelCallId  the model call that covers the tool loop of the turn, when known (the runtime records one model
 *                     call per turn, so every tool call of the turn refers to it)
 */
public record ToolCallScope(Channel channel, @Nullable UUID turnId, @Nullable UUID mcpRequestId,
                            @Nullable UUID modelCallId) {

    /**
     * Scope without a model call.
     *
     * @param channel      entry channel
     * @param turnId       agent turn
     * @param mcpRequestId MCP request
     */
    public ToolCallScope(Channel channel, @Nullable UUID turnId, @Nullable UUID mcpRequestId) {
        this(channel, turnId, mcpRequestId, null);
    }

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
        return new ToolCallScope(channel, Objects.requireNonNull(turnId, "turnId"), null, null);
    }

    /**
     * Scope of a tool loop inside an agent turn whose model call id is known up front.
     *
     * @param channel     entry channel of the turn
     * @param turnId      turn id
     * @param modelCallId id the turn's model call will be recorded under
     * @return the scope
     */
    public static ToolCallScope ofTurn(Channel channel, UUID turnId, UUID modelCallId) {
        return new ToolCallScope(channel, Objects.requireNonNull(turnId, "turnId"), null,
                Objects.requireNonNull(modelCallId, "modelCallId"));
    }

    /**
     * Scope of a tool call made directly through MCP.
     *
     * @param mcpRequestId MCP request id
     * @return the scope
     */
    public static ToolCallScope ofMcpRequest(UUID mcpRequestId) {
        return new ToolCallScope(Channel.MCP, null, Objects.requireNonNull(mcpRequestId, "mcpRequestId"), null);
    }
}
