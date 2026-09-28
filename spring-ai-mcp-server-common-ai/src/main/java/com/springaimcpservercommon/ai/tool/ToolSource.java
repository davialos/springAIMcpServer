package com.springaimcpservercommon.ai.tool;

import com.springaimcpservercommon.core.catalog.CatalogElementRef;

import java.util.Objects;
import java.util.UUID;

/**
 * The source of a tool binding (LLD-07 §2): where the tool's implementation comes from.
 */
public sealed interface ToolSource permits ToolSource.OperationSource, ToolSource.QuerySource,
        ToolSource.McpSource, ToolSource.AgentSource {

    /**
     * A host bean method annotated with {@code @AiExposedAction} (LLD-02 §2).
     *
     * @param opRef catalog reference of kind {@code OP}
     */
    record OperationSource(CatalogElementRef opRef) implements ToolSource {
        /** Validates the reference kind. */
        public OperationSource {
            Objects.requireNonNull(opRef, "opRef");
            if (opRef.kind() != CatalogElementRef.Kind.OP) {
                throw new IllegalArgumentException("opRef must have kind OP: " + opRef);
            }
        }
    }

    /**
     * A published dynamic query executed via the query engine (LLD-05).
     *
     * @param queryId id of the published {@link com.springaimcpservercommon.query.ast.QueryDefinition}
     */
    record QuerySource(UUID queryId) implements ToolSource {
        /** Validates the id. */
        public QuerySource {
            Objects.requireNonNull(queryId, "queryId");
        }
    }

    /**
     * A tool on an external MCP server (LLD-07 §6, v1.x).
     *
     * @param serverId   id of the registered {@code McpServerRegistration}
     * @param remoteTool name of the tool on the remote server (pinned by hash at registration)
     */
    record McpSource(UUID serverId, String remoteTool) implements ToolSource {
        /** Validates fields. */
        public McpSource {
            Objects.requireNonNull(serverId, "serverId");
            Objects.requireNonNull(remoteTool, "remoteTool");
            if (remoteTool.isBlank()) throw new IllegalArgumentException("remoteTool must not be blank");
        }
    }

    /**
     * Another published agent exposed as a single tool ({@code ask_<slug>}, LLD-07 §5.2 v2).
     *
     * @param agentId id of the nested agent
     */
    record AgentSource(UUID agentId) implements ToolSource {
        /** Validates the id. */
        public AgentSource {
            Objects.requireNonNull(agentId, "agentId");
        }
    }
}
