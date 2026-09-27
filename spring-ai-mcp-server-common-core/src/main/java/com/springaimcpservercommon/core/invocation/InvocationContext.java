package com.springaimcpservercommon.core.invocation;

import com.springaimcpservercommon.core.principal.DaiPrincipal;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Immutable context of one invocation (endpoint call, agent turn, MCP request or tool call), carried in a
 * {@link ScopedValue} so it follows structured hand-offs without {@code ThreadLocal} leaks (ADR-0007).
 *
 * <p>Tool calls also receive it through Spring AI's {@code ToolContext} — never through model input.
 *
 * @param invocationId  unique id of this invocation (UUIDv7)
 * @param principal     the calling identity
 * @param workspaceId   workspace the invoked resource belongs to, if any
 * @param channel       entry channel
 * @param mode          what the invocation may do
 * @param traceId       current trace id, if tracing is active
 * @param turnId        agent turn this invocation belongs to, if any
 * @param mcpRequestId  MCP request this invocation belongs to, if any
 * @param deadline      absolute deadline; work must stop after it
 */
public record InvocationContext(
        UUID invocationId,
        DaiPrincipal principal,
        @Nullable UUID workspaceId,
        Channel channel,
        InvocationMode mode,
        @Nullable String traceId,
        @Nullable UUID turnId,
        @Nullable UUID mcpRequestId,
        Instant deadline) {

    /** Scoped value holding the current context. */
    public static final ScopedValue<InvocationContext> CURRENT = ScopedValue.newInstance();

    /** Key under which the context is put into Spring AI's {@code ToolContext} map. */
    public static final String TOOL_CONTEXT_KEY = "dai.invocation";

    /**
     * Validates required components.
     */
    public InvocationContext {
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(deadline, "deadline");
    }

    /**
     * Returns the context bound to the current scope, if any.
     *
     * @return the current context
     */
    public static Optional<InvocationContext> current() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }

    /**
     * Whether the current scope is an AI read (write guard active).
     *
     * @return {@code true} inside an AI/MCP read tool
     */
    public static boolean isAiRead() {
        return CURRENT.isBound() && CURRENT.get().mode() == InvocationMode.AI_READ;
    }

    /**
     * Returns a copy with another mode.
     *
     * @param newMode the mode
     * @return a new context
     */
    public InvocationContext withMode(InvocationMode newMode) {
        return new InvocationContext(invocationId, principal, workspaceId, channel, newMode, traceId, turnId,
                mcpRequestId, deadline);
    }

    /**
     * Runs a task with this context bound.
     *
     * @param task the task
     * @param <T>  result type
     * @return the task's result
     * @throws Exception whatever the task throws
     */
    public <T> T call(Callable<T> task) throws Exception {
        return ScopedValue.where(CURRENT, this).call(task::call);
    }

    /**
     * Runs a task with this context bound.
     *
     * @param task the task
     */
    public void run(Runnable task) {
        ScopedValue.where(CURRENT, this).run(task);
    }
}
