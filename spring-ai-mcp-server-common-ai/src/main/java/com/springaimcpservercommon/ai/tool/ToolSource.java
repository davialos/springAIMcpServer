package com.springaimcpservercommon.ai.tool;

import com.springaimcpservercommon.core.catalog.CatalogElementRef;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The source of a tool binding (LLD-07 §2): where the tool's implementation comes from.
 */
public sealed interface ToolSource permits ToolSource.OperationSource, ToolSource.QuerySource,
        ToolSource.McpSource, ToolSource.AgentSource, ToolSource.CriteriaSource {

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

    /**
     * One of the tools that let a model read data with queries it builds itself (LLD-05 §12): describe the data
     * model, check a query, or run it. Always read-only; the query runs as the caller over entities the catalog
     * exposes to AI.
     *
     * @param tool     which of the three tools
     * @param entities entities the tool may reach, by simple name, class name or {@code entity:} reference; empty
     *                 means every entity the catalog exposes to AI
     * @param maxRows  rows per page this binding allows (also capped by each entity and the global limit)
     */
    record CriteriaSource(CriteriaTool tool, Set<String> entities, int maxRows) implements ToolSource {
        /** Validates fields and copies the entity list. */
        public CriteriaSource {
            Objects.requireNonNull(tool, "tool");
            entities = Set.copyOf(entities);
            if (maxRows < 1 || maxRows > 1000) {
                throw new IllegalArgumentException("maxRows must be in [1, 1000]");
            }
        }
    }

    /** The three criteria tools. */
    enum CriteriaTool {
        /** Lists the entities, columns, types, operators and relations the caller may query. */
        DESCRIBE,
        /** Checks a query and explains it without reading data. */
        VALIDATE,
        /** Checks and runs a query, returning one page of rows. */
        EXECUTE
    }
}
