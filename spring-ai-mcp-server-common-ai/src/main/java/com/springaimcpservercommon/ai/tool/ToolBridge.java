package com.springaimcpservercommon.ai.tool;

import com.springaimcpservercommon.ai.agent.AgentDefinition;
import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.catalog.EffectiveOperation;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import org.jspecify.annotations.NullMarked;
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

    private final ToolBindingLoader bindingLoader;
    private final OperationCallbackFactory operationFactory;
    private final QueryCallbackFactory queryFactory;
    private final SecuredToolCallback.ToolPermissionChecker permissionChecker;

    /**
     * Constructs the bridge with all required ports.
     *
     * @param bindingLoader     loads ToolBindings from the catalog store
     * @param operationFactory  builds delegate callbacks for operation-backed tools
     * @param queryFactory      builds delegate callbacks for query-backed tools
     * @param permissionChecker runtime permission check per call
     */
    public ToolBridge(ToolBindingLoader bindingLoader,
                       OperationCallbackFactory operationFactory,
                       QueryCallbackFactory queryFactory,
                       SecuredToolCallback.ToolPermissionChecker permissionChecker) {
        this.bindingLoader = Objects.requireNonNull(bindingLoader, "bindingLoader");
        this.operationFactory = Objects.requireNonNull(operationFactory, "operationFactory");
        this.queryFactory = Objects.requireNonNull(queryFactory, "queryFactory");
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
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
            ToolCallback delegate = resolveDelegate(binding, principal, catalog);
            if (delegate == null) {
                LOG.warn("Cannot resolve delegate for tool {} (source {}); skipping for agent {} principal {}",
                        binding.toolName(), binding.source(), agent.slug(), principal.principalId());
                continue;
            }
            result.add(new SecuredToolCallback(
                    delegate, binding, principal, authentication,
                    new AtomicInteger(0), permissionChecker));
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

        ToolCallback delegate = resolveDelegate(binding, principal, catalog);
        if (delegate == null) return null;
        return new SecuredToolCallback(delegate, binding, principal, authentication,
                new AtomicInteger(0), permissionChecker);
    }

    private @org.jspecify.annotations.Nullable ToolCallback resolveDelegate(
            ToolBinding binding, DaiPrincipal principal, EffectiveCatalog catalog) {

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
            case ToolSource.AgentSource ignored -> {
                // Sub-agent tool delegation is a future capability (OQ-pending).
                LOG.debug("Agent-backed tool {} is not yet supported; skipping", binding.toolName());
                yield null;
            }
        };
    }
}
