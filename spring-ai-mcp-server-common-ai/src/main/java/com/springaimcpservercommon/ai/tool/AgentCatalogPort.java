package com.springaimcpservercommon.ai.tool;

import com.springaimcpservercommon.ai.agent.AgentDefinition;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Port: looks up a published {@link AgentDefinition} by id for sub-agent tool delegation (LLD-07 §5.2).
 *
 * <p>Implemented by the persistence module ({@code dai_agent} table) and injected into
 * {@link ToolBridge} via the {@code autoconfigure} module. When no implementation is registered
 * the {@link ToolSource.AgentSource} case in {@link ToolBridge} is skipped gracefully.
 *
 * <p>Implementations must be thread-safe (called concurrently on request threads).
 */
@NullMarked
@FunctionalInterface
public interface AgentCatalogPort {

    /**
     * Looks up the current revision of a published agent.
     *
     * @param agentId the agent's unique id
     * @return the agent definition, or {@code null} if the agent does not exist or is unpublished
     */
    @Nullable AgentDefinition findById(UUID agentId);
}
