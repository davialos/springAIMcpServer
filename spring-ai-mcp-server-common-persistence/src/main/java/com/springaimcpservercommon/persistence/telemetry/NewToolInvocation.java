package com.springaimcpservercommon.persistence.telemetry;

import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.invocation.Channel;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * A completed tool invocation to record: the central "what did the AI do on whose behalf" fact.
 *
 * @param id                 invocation id (UUIDv7)
 * @param startedAt          start
 * @param endedAt            end
 * @param channel            entry channel; CHAT/PLAYGROUND need a turn, MCP needs an MCP request
 * @param turnId             agent turn, if any
 * @param modelCallId        model call that requested the tool, if any
 * @param providerToolCallId provider's tool call id, if any
 * @param mcpRequestId       MCP request, if any
 * @param workspaceId        workspace
 * @param principalId        caller the tool ran as
 * @param toolName           tool name ({@code ^[a-z][a-z0-9_]{2,63}$})
 * @param elementRef         invoked catalog element (op, entity, query, agent or mcp kind)
 * @param bindingRevisionId  revision that bound the tool, if any
 * @param accessMode         READ or PROPOSE
 * @param argsHash           {@code sha256:} of the canonical arguments
 * @param argsRedactedJson   redacted arguments as JSON, if kept
 * @param status             result status; PROPOSED requires {@code proposalId} and PROPOSE mode
 * @param rowCount           rows returned, if applicable
 * @param resultHash         {@code sha256:} of the result, if any
 * @param truncated          whether the result was truncated
 * @param errorCode          error code, if any
 * @param writeViolation     whether the write guard vetoed a write attempt
 * @param proposalId         created proposal, when status is PROPOSED
 */
public record NewToolInvocation(
        UUID id,
        Instant startedAt,
        Instant endedAt,
        Channel channel,
        @Nullable UUID turnId,
        @Nullable UUID modelCallId,
        @Nullable String providerToolCallId,
        @Nullable UUID mcpRequestId,
        UUID workspaceId,
        UUID principalId,
        String toolName,
        CatalogElementRef elementRef,
        @Nullable UUID bindingRevisionId,
        ToolAccessMode accessMode,
        String argsHash,
        @Nullable String argsRedactedJson,
        ToolInvocationStatus status,
        @Nullable Integer rowCount,
        @Nullable String resultHash,
        boolean truncated,
        @Nullable String errorCode,
        boolean writeViolation,
        @Nullable UUID proposalId) {
}
