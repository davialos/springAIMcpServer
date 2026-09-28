package com.springaimcpservercommon.ai.tool;

import com.springaimcpservercommon.ai.agent.AgentDefinition;
import com.springaimcpservercommon.ai.runtime.AgentInvoker;
import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.catalog.EffectiveOperation;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.security.core.Authentication;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Assembles the per-request list of {@link SecuredToolCallback} instances for an agent turn (LLD-07 §3).
 *
 * <p>For each {@link ToolBindingRef} in the {@link AgentDefinition}:
 * <ol>
 *   <li>Look up the {@link ToolBinding} from the catalog store.</li>
 *   <li>Resolve the delegate {@link ToolCallback} from the appropriate source (operation, query, MCP, agent).</li>
 *   <li>Wrap it in a {@link SecuredToolCallback} that enforces runtime permission, arg-constraints and write mode.</li>
 * </ol>
 *
 * <p>A new {@link AtomicInteger} call counter is allocated per (tool, turn) so the per-turn cap is correctly scoped.
 * The bridge is stateless and safe to share across threads; callers must pass per-request state in the arguments.
 */
@NullMarked
public final class ToolBridge {

    private static final Logger LOG = LoggerFactory.getLogger(ToolBridge.class);

    /** Port: loads a ToolBinding by id and revision. */
    @FunctionalInterface
    public interface ToolBindingLoader {
        /** @return the binding for the given id, or {@code null} if not found */
        @org.jspecify.annotations.Nullable
        ToolBinding load(UUID bindingId, int revision);
    }

    /** Port: creates a delegate ToolCallback for an operation-backed binding. */
    @FunctionalInterface
    public interface OperationCallbackFactory {
        ToolCallback create(EffectiveOperation operation, ToolBinding binding, DaiPrincipal principal);
    }

    /** Port: creates a delegate ToolCallback for a query-backed binding. */
    @FunctionalInterface
    public interface QueryCallbackFactory {
        ToolCallback create(UUID queryId, ToolBinding binding, DaiPrincipal principal);
    }

    /**
     * Port: creates a delegate {@link ToolCallback} for an {@link ToolSource.AgentSource}-backed
     * binding (LLD-07 §5.2). Optional — when absent, {@code AgentSource} bindings are skipped
     * gracefully (logged at {@code WARN}).
     *
     * <p>Implementations look up the target {@link AgentDefinition} and return an
     * {@link AgentDelegateToolCallback} that invokes it synchronously. The implementation should
     * use a lazy reference (e.g. {@code ObjectProvider}) to the {@code AgentInvoker} to avoid
     * a circular Spring bean dependency.
     */
    @FunctionalInterface
    public interface AgentCallbackFactory {
        /**
         * @param agentId        id of the target agent
         * @param binding        the governing tool binding
         * @param principal      the calling principal
         * @param authentication Spring Security authentication (forwarded to the sub-agent)
         * @return the delegate callback, or {@code null} if the target agent is unknown
         */
        @Nullable ToolCallback create(UUID agentId, ToolBinding binding,
                                      DaiPrincipal principal, Authentication authentication);
    }

    private final ToolBindingLoader bindingLoader;
    private final OperationCallbackFactory operationFactory;
    private final QueryCallbackFactory queryFactory;
    private final @Nullable AgentCallbackFactory agentFactory;
    private final SecuredToolCallback.ToolPermissionChecker permissionChecker;
    private final ProposalService proposalService;

    /**
     * Constructs the bridge with all required ports and no sub-agent delegation support.
     *
     * @param bindingLoader     loads ToolBindings from the catalog store
     * @param operationFactory  builds delegate callbacks for operation-backed tools
     * @param queryFactory      builds delegate callbacks for query-backed tools
     * @param permissionChecker runtime permission check per call
     * @param proposalService   creates ChangeProposal records for PROPOSE-mode tools
     */
    public ToolBridge(ToolBindingLoader bindingLoader,
                       OperationCallbackFactory operationFactory,
                       QueryCallbackFactory queryFactory,
                       SecuredToolCallback.ToolPermissionChecker permissionChecker,
                       ProposalService proposalService) {
        this(bindingLoader, operationFactory, queryFactory, null, permissionChecker, proposalService);
    }

    /**
     * Constructs the bridge with all required ports plus optional sub-agent delegation.
     *
     * @param bindingLoader     loads ToolBindings from the catalog store
     * @param operationFactory  builds delegate callbacks for operation-backed tools
     * @param queryFactory      builds delegate callbacks for query-backed tools
     * @param agentFactory      builds delegate callbacks for agent-backed tools; {@code null} disables AgentSource routing
     * @param permissionChecker runtime permission check per call
     * @param proposalService   creates ChangeProposal records for PROPOSE-mode tools
     */
    public ToolBridge(ToolBindingLoader bindingLoader,
                       OperationCallbackFactory operationFactory,
                       QueryCallbackFactory queryFactory,
                       @Nullable AgentCallbackFactory agentFactory,
                       SecuredToolCallback.ToolPermissionChecker permissionChecker,
                       ProposalService proposalService) {
        this.bindingLoader = Objects.requireNonNull(bindingLoader, "bindingLoader");
        this.operationFactory = Objects.requireNonNull(operationFactory, "operationFactory");
        this.queryFactory = Objects.requireNonNull(queryFactory, "queryFactory");
        this.agentFactory = agentFactory; // optional
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.proposalService = Objects.requireNonNull(proposalService, "proposalService");
    }

    /**
     * Builds the tool callback list for one agent turn.
     *
     * <p>Bindings that are not found in the catalog or whose source cannot be resolved are skipped
     * with a warning — the agent must not fail hard when a tool is temporarily unavailable (LLD-12 §4).
     *
     * @param agent          the agent definition governing this turn
     * @param principal      the calling principal
     * @param authentication Spring Security authentication for the caller
     * @param catalog        the effective catalog snapshot for this workspace
     * @return an ordered, immutable list of secured tool callbacks
     */
    public List<ToolCallback> buildCallbacks(AgentDefinition agent, DaiPrincipal principal,
                                              Authentication authentication, EffectiveCatalog catalog) {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(authentication, "authentication");
        Objects.requireNonNull(catalog, "catalog");

        List<ToolCallback> result = new ArrayList<>(agent.tools().size());
        for (ToolBindingRef ref : agent.tools()) {
            ToolBinding binding = bindingLoader.load(ref.bindingId(), ref.revision());
            if (binding == null) {
                LOG.warn("ToolBinding {} rev {} not found; skipping for agent {} principal {}",
                        ref.bindingId(), ref.revision(), agent.slug(), principal.principalId());
                continue;
            }
            ToolCallback delegate = resolveDelegate(binding, principal, authentication, catalog);
            if (delegate == null) {
                LOG.warn("Cannot resolve delegate for tool {} (source {}); skipping for agent {} principal {}",
                        binding.toolName(), binding.source(), agent.slug(), principal.principalId());
                continue;
            }
            result.add(new SecuredToolCallback(
                    delegate, binding, principal, authentication,
                    new AtomicInteger(0), permissionChecker, proposalService));
        }
        return List.copyOf(result);
    }

    /**
     * Builds a single secured tool callback for an already-resolved {@link ToolBinding}.
     *
     * <p>Used by the MCP server layer which already has the binding in hand and does not need
     * the agent-scoped lookup path.
     *
     * @param binding        the tool binding
     * @param principal      calling principal
     * @param authentication Spring Security authentication
     * @param catalog        effective catalog snapshot
     * @return the secured callback, or {@code null} if the delegate cannot be resolved
     */
    public @org.jspecify.annotations.Nullable ToolCallback buildCallback(ToolBinding binding, DaiPrincipal principal,
                                                                           Authentication authentication,
                                                                           EffectiveCatalog catalog) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(authentication, "authentication");
        Objects.requireNonNull(catalog, "catalog");

        ToolCallback delegate = resolveDelegate(binding, principal, authentication, catalog);
        if (delegate == null) return null;
        return new SecuredToolCallback(delegate, binding, principal, authentication,
                new AtomicInteger(0), permissionChecker, proposalService);
    }

    /**
     * Builds the default {@link AgentCallbackFactory} that looks up agents via {@link AgentCatalogPort}
     * and invokes them via a lazily-resolved {@link AgentInvoker}.
     *
     * <p>The {@code invokerSupplier} is called at request time (not at construction time) so the
     * autoconfigure module can pass {@code ObjectProvider::getIfAvailable} to break the circular
     * bean dependency between {@link ToolBridge} and {@link AgentInvoker}.
     *
     * @param invokerSupplier lazy reference to the AgentInvoker (may return {@code null} if not yet available)
     * @param agentCatalog    port for loading AgentDefinitions by id
     * @return the factory
     */
    public static AgentCallbackFactory defaultAgentCallbackFactory(
            java.util.function.Supplier<@Nullable AgentInvoker> invokerSupplier,
            AgentCatalogPort agentCatalog) {
        Objects.requireNonNull(invokerSupplier, "invokerSupplier");
        Objects.requireNonNull(agentCatalog, "agentCatalog");
        return (agentId, binding, principal, authentication) -> {
            AgentInvoker invoker = invokerSupplier.get();
            if (invoker == null) return null;
            AgentDefinition target = agentCatalog.findById(agentId);
            if (target == null) return null;
            return new AgentDelegateToolCallback(invoker, target, principal, authentication, binding);
        };
    }

    private @Nullable ToolCallback resolveDelegate(
            ToolBinding binding, DaiPrincipal principal,
            Authentication authentication, EffectiveCatalog catalog) {

        return switch (binding.source()) {
            case ToolSource.OperationSource(var opRef) -> {
                var opOpt = catalog.operation(opRef);
                if (opOpt.isEmpty()) {
                    LOG.warn("Operation {} not found in catalog for tool {}", opRef, binding.toolName());
                    yield null;
                }
                yield operationFactory.create(opOpt.get(), binding, principal);
            }
            case ToolSource.QuerySource(var queryId) ->
                    queryFactory.create(queryId, binding, principal);
            case ToolSource.McpSource ignored -> {
                // MCP remote tool delegation is handled by the mcp module's McpToolCallbackFactory.
                // If no MCP factory has been registered (it's optional), skip gracefully.
                LOG.debug("MCP-backed tool {} has no registered factory; skipping", binding.toolName());
                yield null;
            }
            case ToolSource.AgentSource(var agentId) -> {
                if (agentFactory == null) {
                    LOG.warn("Agent-backed tool {} cannot be resolved: no AgentCallbackFactory registered; skipping",
                            binding.toolName());
                    yield null;
                }
                ToolCallback cb = agentFactory.create(agentId, binding, principal, authentication);
                if (cb == null) {
                    LOG.warn("Agent {} not found for tool {}; skipping", agentId, binding.toolName());
                }
                yield cb;
            }
        };
    }
}
