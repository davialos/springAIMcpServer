package com.springaimcpservercommon.webmvc.endpoint;

import com.springaimcpservercommon.core.catalog.CatalogElementRef;

import java.util.Map;
import java.util.UUID;

/**
 * What backs a dynamic endpoint (LLD-04 §2).
 *
 * <p>The three backing types map directly to the three execution paths:
 * <ul>
 *   <li>{@link QueryBacking} — runs a published dynamic query (LLD-05).</li>
 *   <li>{@link AgentBacking} — invokes a published agent with a rendered system prompt (LLD-06).</li>
 *   <li>{@link OperationBacking} — calls a host {@code @AiExposedAction} method via proxy.</li>
 * </ul>
 */
public sealed interface Backing permits Backing.QueryBacking, Backing.AgentBacking, Backing.OperationBacking {

    /**
     * Runs a published dynamic query.
     *
     * @param queryId       the query to run
     * @param paramBindings endpoint param name → query param name mappings
     */
    record QueryBacking(UUID queryId, Map<String, String> paramBindings) implements Backing {
        public QueryBacking {
            paramBindings = Map.copyOf(paramBindings);
        }
    }

    /**
     * Invokes a published agent, rendering the user input from a template.
     *
     * @param agentId       the agent to invoke
     * @param inputTemplate Mustache-like template; {@code {paramName}} placeholders map to endpoint params
     */
    record AgentBacking(UUID agentId, String inputTemplate) implements Backing {}

    /**
     * Calls a host bean method decorated with {@code @AiExposedAction}.
     *
     * @param operation     catalog reference to the operation
     * @param paramBindings endpoint param name → method param name mappings
     */
    record OperationBacking(CatalogElementRef operation, Map<String, String> paramBindings) implements Backing {
        public OperationBacking {
            paramBindings = Map.copyOf(paramBindings);
        }
    }
}
