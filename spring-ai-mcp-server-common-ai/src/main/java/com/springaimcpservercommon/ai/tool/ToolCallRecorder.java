package com.springaimcpservercommon.ai.tool;

import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * SPI: receives one record per tool call the runtime handled, whatever its outcome (F-72, LLD-07 §3, LLD-15).
 * The store-backed implementation lives in {@code autoconfigure}.
 *
 * <p>Implementations must return quickly and must never throw: recording is not allowed to slow or fail a tool
 * call, a turn or the host (LLD-12). Records hold hashes, never the arguments or the result themselves.
 */
@FunctionalInterface
public interface ToolCallRecorder {

    /** Discards every record. */
    ToolCallRecorder NOOP = call -> { };

    /**
     * Records a finished tool call.
     *
     * @param call what happened
     */
    void record(ToolCall call);

    /**
     * One handled tool call.
     *
     * @param id            invocation id (UUIDv7)
     * @param startedAt     when the call started
     * @param endedAt       when it finished
     * @param scope         channel and turn or MCP request
     * @param binding       the governing binding (workspace, tool name, source, write mode)
     * @param principalId   caller the tool ran as
     * @param elementRef    catalog element the tool is backed by
     * @param argsSha256    {@code sha256:} of the arguments as the model sent them
     * @param status        outcome
     * @param resultSha256  {@code sha256:} of the result, when the delegate ran
     * @param truncated     whether the result was cut by the size limit
     * @param errorCode     stable error code, if any
     * @param writeViolation whether the write guard vetoed a write attempt
     * @param proposalId    created proposal, when the status is {@link ToolResultStatus#PROPOSED}
     */
    record ToolCall(UUID id, Instant startedAt, Instant endedAt, ToolCallScope scope, ToolBinding binding,
                    UUID principalId, CatalogElementRef elementRef, String argsSha256, ToolResultStatus status,
                    @Nullable String resultSha256, boolean truncated, @Nullable String errorCode,
                    boolean writeViolation, @Nullable UUID proposalId) {

        /** Validates the required components. */
        public ToolCall {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(endedAt, "endedAt");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(principalId, "principalId");
            Objects.requireNonNull(elementRef, "elementRef");
            Objects.requireNonNull(argsSha256, "argsSha256");
            Objects.requireNonNull(status, "status");
        }
    }
}
