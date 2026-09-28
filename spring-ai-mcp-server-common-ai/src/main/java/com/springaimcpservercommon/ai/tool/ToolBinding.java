package com.springaimcpservercommon.ai.tool;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A published tool binding: a catalog capability exposed to an agent (LLD-07 §2).
 *
 * <p>Tool names must be stable (never renamed after v1.0) because evals, audit records, and MCP
 * client configurations key on them. They follow the MCP naming convention:
 * {@code ^[a-z][a-z0-9_]{2,63}$} (snake_case, no dots or camelCase).
 *
 * @param id                  unique binding id (UUIDv7)
 * @param revision            monotonically increasing revision
 * @param workspaceId         owning workspace
 * @param toolName            stable tool name exposed to the model and MCP clients
 * @param source              where the implementation comes from
 * @param descriptionOverride override for the catalog description; {@code null} uses the catalog's effective description
 * @param argConstraints      per-argument server-side constraints keyed by parameter name
 * @param writeMode           whether to execute directly or create a reviewed proposal
 * @param returnDirect        if {@code true}, the model returns the tool result directly without a follow-up turn
 * @param timeout             per-call timeout (virtual thread + Future.get); defaults to tool default
 * @param maxCallsPerTurn     cap on how many times this tool may be called in one turn
 * @param result              result post-processing policy
 * @param mcpExposed          whether this binding is also exposed over MCP (LLD-07 §5.2)
 */
public record ToolBinding(
        UUID id,
        int revision,
        UUID workspaceId,
        String toolName,
        ToolSource source,
        @Nullable String descriptionOverride,
        Map<String, ArgConstraint> argConstraints,
        WriteMode writeMode,
        boolean returnDirect,
        Duration timeout,
        int maxCallsPerTurn,
        ResultPolicy result,
        boolean mcpExposed) {

    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{2,63}$");

    /** Validates fields and defensively copies the constraints map. */
    public ToolBinding {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(toolName, "toolName");
        if (!TOOL_NAME_PATTERN.matcher(toolName).matches()) {
            throw new IllegalArgumentException("toolName must match ^[a-z][a-z0-9_]{2,63}$: " + toolName);
        }
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(writeMode, "writeMode");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maxCallsPerTurn < 1) {
            throw new IllegalArgumentException("maxCallsPerTurn must be >= 1");
        }
        Objects.requireNonNull(result, "result");
        argConstraints = Map.copyOf(argConstraints);
    }
}
