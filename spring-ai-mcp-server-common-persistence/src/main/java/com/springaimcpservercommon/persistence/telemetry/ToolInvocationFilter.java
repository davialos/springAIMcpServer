package com.springaimcpservercommon.persistence.telemetry;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Optional narrowing of a workspace's tool invocation list; a {@code null} component does not filter.
 *
 * @param toolName       only this tool
 * @param status         only this status
 * @param principalId    only calls made as this caller
 * @param violationsOnly only calls the AI write guard vetoed
 * @param problemsOnly   only calls whose status is not OK or EMPTY
 */
public record ToolInvocationFilter(@Nullable String toolName, @Nullable ToolInvocationStatus status,
                                   @Nullable UUID principalId, boolean violationsOnly, boolean problemsOnly) {

    /** No filtering. */
    public static final ToolInvocationFilter NONE = new ToolInvocationFilter(null, null, null, false, false);

    /**
     * Only write-guard vetoes (the earlier {@code violationsOnly} switch).
     *
     * @param violationsOnly whether to restrict to vetoes
     * @return the filter
     */
    public static ToolInvocationFilter violations(boolean violationsOnly) {
        return new ToolInvocationFilter(null, null, null, violationsOnly, false);
    }
}
