package com.springaimcpservercommon.webmvc.endpoint;

import com.springaimcpservercommon.ai.runtime.AgentInvoker;
import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.json.CanonicalJson;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.webmvc.problem.ProblemCode;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Real {@link GenericDynamicHandler.BackingExecutor} that dispatches to the correct executor
 * based on the backing type of the {@link EndpointDefinition} (LLD-04 §5).
 *
 * <p>Dispatch table:
 * <ul>
 *   <li>{@link Backing.QueryBacking} → {@link QueryBackingHandler} (dynamic query via LLD-05)</li>
 *   <li>{@link Backing.AgentBacking} → {@link AgentBackingHandler} (sync agent turn via LLD-06)</li>
 *   <li>{@link Backing.OperationBacking} → {@link OperationBackingHandler} (host method via proxy)</li>
 * </ul>
 *
 * <p>All three handler ports are {@link FunctionalInterface}s so the {@code autoconfigure}
 * module can wire them with {@code @ConditionalOnMissingBean}. If a handler port is absent
 * (the module is not on the classpath), the backing type returns a safe error to the caller
 * instead of failing the host.
 *
 * <p>This class is not a Spring {@code @Component} — it is registered as a bean by the
 * {@code autoconfigure} module.
 */
@NullMarked
public final class DispatchingBackingExecutor implements GenericDynamicHandler.BackingExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(DispatchingBackingExecutor.class);

    // ─── Handler ports ───────────────────────────────────────────────────────

    /**
     * Port: executes a {@link Backing.QueryBacking}.
     *
     * <p>Implemented by the {@code query} module adapter in {@code autoconfigure}.
     * Returns a JSON array of result rows (the raw query result before shaping).
     */
    @FunctionalInterface
    public interface QueryBackingHandler {
        /**
         * @param queryId    the query to run
         * @param bindings   param name → value from the endpoint params (pre-mapped via {@code paramBindings})
         * @param principal  calling principal
         * @return JSON string result (array or object)
         * @throws GenericDynamicHandler.BackingException on execution failure
         */
        String execute(UUID queryId, Map<String, Object> bindings, DaiPrincipal principal)
                throws GenericDynamicHandler.BackingException;
    }

    /**
     * Port: executes a {@link Backing.AgentBacking} as a synchronous turn.
     *
     * <p>Implemented by the {@code ai} module adapter in {@code autoconfigure}.
     * The rendered input template is used as the user message.
     */
    @FunctionalInterface
    public interface AgentBackingHandler {
        /**
         * @param agentId        the agent to invoke
         * @param renderedInput  user message (template already rendered with endpoint params)
         * @param principal      calling principal
         * @param authentication Spring Security authentication for tool permission checks
         * @return JSON string result (the agent's final message and metadata)
         * @throws GenericDynamicHandler.BackingException on execution failure
         */
        String execute(UUID agentId, String renderedInput, DaiPrincipal principal,
                       Authentication authentication)
                throws GenericDynamicHandler.BackingException;
    }

    /**
     * Port: invokes an {@link Backing.OperationBacking} via a Spring proxy.
     *
     * <p>Implemented by the {@code webmvc} or host code. Read-only operations execute immediately;
     * mutating operations create a {@code ChangeProposal} and return {@code 202 Accepted}.
     */
    @FunctionalInterface
    public interface OperationBackingHandler {
        /**
         * @param operation  catalog reference to the operation
         * @param bindings   param name → value (pre-mapped via {@code paramBindings})
         * @param principal  calling principal
         * @return JSON string result
         * @throws GenericDynamicHandler.BackingException on execution failure
         */
        String execute(CatalogElementRef operation, Map<String, Object> bindings, DaiPrincipal principal)
                throws GenericDynamicHandler.BackingException;
    }

    // ─── State ───────────────────────────────────────────────────────────────

    private final QueryBackingHandler queryHandler;
    private final AgentBackingHandler agentHandler;
    private final OperationBackingHandler operationHandler;

    /**
     * Creates the dispatcher with all three backing handlers.
     *
     * @param queryHandler     handles query-backed endpoints
     * @param agentHandler     handles agent-backed endpoints
     * @param operationHandler handles operation-backed endpoints
     */
    public DispatchingBackingExecutor(QueryBackingHandler queryHandler,
                                       AgentBackingHandler agentHandler,
                                       OperationBackingHandler operationHandler) {
        this.queryHandler = Objects.requireNonNull(queryHandler, "queryHandler");
        this.agentHandler = Objects.requireNonNull(agentHandler, "agentHandler");
        this.operationHandler = Objects.requireNonNull(operationHandler, "operationHandler");
    }

    // ─── BackingExecutor ─────────────────────────────────────────────────────

    @Override
    public String execute(EndpointDefinition def, DaiPrincipal principal,
                           Map<String, Object> params)
            throws GenericDynamicHandler.BackingException {

        return switch (def.backing()) {
            case Backing.QueryBacking q -> {
                Map<String, Object> bindings = applyBindings(params, q.paramBindings());
                LOG.debug("Executing QueryBacking query={} for endpoint={}", q.queryId(), def.id());
                yield queryHandler.execute(q.queryId(), bindings, principal);
            }

            case Backing.AgentBacking a -> {
                String input = renderInputTemplate(a.inputTemplate(), params);
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth == null) {
                    throw new GenericDynamicHandler.BackingException(
                            ProblemCode.ACCESS_DENIED, "No authenticated session available.");
                }
                LOG.debug("Executing AgentBacking agent={} for endpoint={}", a.agentId(), def.id());
                yield agentHandler.execute(a.agentId(), input, principal, auth);
            }

            case Backing.OperationBacking op -> {
                Map<String, Object> bindings = applyBindings(params, op.paramBindings());
                LOG.debug("Executing OperationBacking op={} for endpoint={}", op.operation(), def.id());
                yield operationHandler.execute(op.operation(), bindings, principal);
            }
        };
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Remaps endpoint parameter names to backing parameter names using the binding map.
     * Passes through any param whose name is not in the binding map unchanged.
     */
    private static Map<String, Object> applyBindings(Map<String, Object> params,
                                                       Map<String, String> bindings) {
        if (bindings.isEmpty()) return params;
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>(params.size());
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String mapped = bindings.getOrDefault(entry.getKey(), entry.getKey());
            result.put(mapped, entry.getValue());
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    /**
     * Renders a Mustache-style input template by replacing {@code {paramName}} placeholders
     * with the corresponding parameter values from the endpoint params map.
     */
    private static String renderInputTemplate(String template, Map<String, Object> params) {
        String rendered = template;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            Object val = entry.getValue();
            if (val != null) {
                rendered = rendered.replace("{" + entry.getKey() + "}", val.toString());
            }
        }
        return rendered;
    }

    // ─── Default no-op handlers ──────────────────────────────────────────────
    // Provided via autoconfigure; these are documented here for reference only.
    // The autoconfigure module registers them with @ConditionalOnMissingBean.

    /**
     * Creates a default agent backing handler that wraps {@link AgentInvoker}.
     *
     * <p>Called by {@code DaiWebMvcAutoConfiguration} when wiring the dispatcher.
     *
     * @param invoker   the agent invoker bean
     * @param resolver  resolves agent definition by id
     * @return the handler
     */
    public static AgentBackingHandler defaultAgentBackingHandler(
            AgentInvoker invoker,
            AgentDefinitionResolver resolver) {
        return (agentId, renderedInput, principal, authentication) -> {
            AgentInvoker.AgentChatRequest request =
                    new AgentInvoker.AgentChatRequest(null, renderedInput, UUID.randomUUID().toString(), null,
                            com.springaimcpservercommon.core.invocation.Channel.ENDPOINT);
            com.springaimcpservercommon.ai.agent.AgentDefinition def = resolver.resolve(agentId);
            if (def == null) {
                throw new GenericDynamicHandler.BackingException(
                        ProblemCode.RESOURCE_SUSPENDED,
                        "Agent " + agentId + " is not published.");
            }
            try {
                AgentInvoker.SyncChatResult result = invoker.invoke(def, request, principal, authentication);
                return syncResultToJson(result);
            } catch (AgentInvoker.AgentInvocationException e) {
                throw new GenericDynamicHandler.BackingException(mapCode(e.code()), e.getMessage());
            }
        };
    }

    /** Port: resolves an {@link com.springaimcpservercommon.ai.agent.AgentDefinition} by id. */
    @FunctionalInterface
    public interface AgentDefinitionResolver {
        com.springaimcpservercommon.ai.agent.@org.jspecify.annotations.Nullable AgentDefinition resolve(UUID agentId);
    }

    private static String syncResultToJson(AgentInvoker.SyncChatResult result) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("conversationId", result.conversationId().toString());
        m.put("turnId", result.turnId().toString());
        m.put("message", result.message());
        var toolCallMaps = result.toolCalls().stream()
                .map(tc -> {
                    java.util.LinkedHashMap<String, Object> t = new java.util.LinkedHashMap<>();
                    t.put("callId", tc.callId());
                    t.put("tool", tc.tool());
                    t.put("status", tc.status());
                    return t;
                })
                .toList();
        m.put("toolCalls", toolCallMaps);
        java.util.LinkedHashMap<String, Object> usage = new java.util.LinkedHashMap<>();
        usage.put("inputTokens", result.usage().inputTokens());
        usage.put("outputTokens", result.usage().outputTokens());
        m.put("usage", usage);
        return CanonicalJson.write(m);
    }

    private static ProblemCode mapCode(String code) {
        return switch (code) {
            case "agent-disabled" -> ProblemCode.ENDPOINT_DISABLED;
            case "budget-exhausted" -> ProblemCode.BUDGET_EXHAUSTED;
            case "turn-timeout" -> ProblemCode.EXECUTION_TIMEOUT;
            default -> ProblemCode.EXECUTION_ERROR;
        };
    }
}
