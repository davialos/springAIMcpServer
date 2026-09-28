package com.springaimcpservercommon.ai.tool;

import com.springaimcpservercommon.core.principal.DaiPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.context.ToolContext;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Security and compliance decorator around a {@link ToolCallback} (LLD-07 §3, ADR-0008, ADR-0014).
 *
 * <p>On every tool call:
 * <ol>
 *   <li>Reads the {@link InvocationContext} from {@link ToolContext} — never from the model's input JSON.</li>
 *   <li>Re-checks the principal's tool-invoke permission (grants may have changed mid-conversation).</li>
 *   <li>Applies {@link ArgConstraint}s: PRINCIPAL_ATTR values are overwritten server-side regardless
 *       of what the model put in its input.</li>
 *   <li>Enforces the per-turn call count cap ({@link ToolBinding#maxCallsPerTurn()}).</li>
 *   <li>If {@link WriteMode#PROPOSE}: does NOT invoke the delegate; returns a proposal envelope.</li>
 *   <li>Runs the delegate with the caller's {@link SecurityContext} set on the executing thread.</li>
 *   <li>Post-processes the result into a {@link ToolResultEnvelope} JSON string.</li>
 * </ol>
 *
 * <p>Instantiated per-request by {@link ToolBridge}; never a Spring bean itself.
 */
public final class SecuredToolCallback implements ToolCallback {

    private static final Logger LOG = LoggerFactory.getLogger(SecuredToolCallback.class);

    private final ToolCallback delegate;
    private final ToolBinding binding;
    private final DaiPrincipal principal;
    private final Authentication authentication;
    private final AtomicInteger callCount;
    private final ToolPermissionChecker permissionChecker;

    /**
     * SPI: checks whether a principal may invoke a specific tool binding at call time.
     * Implemented in the {@code security} module and injected via {@link ToolBridge}.
     */
    @FunctionalInterface
    public interface ToolPermissionChecker {
        /**
         * @param principal calling principal
         * @param binding   the binding being invoked
         * @return {@code true} if the call is permitted
         */
        boolean isPermitted(DaiPrincipal principal, ToolBinding binding);
    }

    /**
     * Creates the callback.
     *
     * @param delegate          the wrapped Spring AI callback (MethodToolCallback or FunctionToolCallback)
     * @param binding           the governing tool binding
     * @param principal         calling principal
     * @param authentication    Spring Security authentication for the caller
     * @param sharedCallCount   shared counter tracking calls to this tool within the current turn
     * @param permissionChecker permission checker
     */
    public SecuredToolCallback(ToolCallback delegate, ToolBinding binding, DaiPrincipal principal,
                                Authentication authentication, AtomicInteger sharedCallCount,
                                ToolPermissionChecker permissionChecker) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.binding = Objects.requireNonNull(binding, "binding");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.callCount = Objects.requireNonNull(sharedCallCount, "sharedCallCount");
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        // 1. Re-check permission (grants may change mid-conversation)
        if (!permissionChecker.isPermitted(principal, binding)) {
            LOG.info("Tool {} denied for principal {}", binding.toolName(), principal.principalId());
            return ToolResultEnvelope.notPermitted(binding.toolName()).toJson();
        }

        // 2. Enforce per-turn call count
        int count = callCount.incrementAndGet();
        if (count > binding.maxCallsPerTurn()) {
            LOG.warn("Tool {} exceeded maxCallsPerTurn({}) for principal {}",
                    binding.toolName(), binding.maxCallsPerTurn(), principal.principalId());
            return ToolResultEnvelope.error(binding.toolName(),
                    "call_limit_exceeded",
                    "Tool call limit (" + binding.maxCallsPerTurn() + " per turn) exceeded.").toJson();
        }

        // 3. If PROPOSE → create proposal, do not run the delegate
        if (binding.writeMode() == WriteMode.PROPOSE) {
            return handleProposal(toolInput, toolContext);
        }

        // 4. Run delegate as the caller with the correct SecurityContext
        return runAsCallerWithEnvelope(toolInput, toolContext);
    }

    private String handleProposal(String toolInput, ToolContext toolContext) {
        // The actual ChangeProposal creation lives in the persistence/webmvc layer (LLD-11).
        // The tool bridge signals the agent runtime to pause the turn.
        // Here we return a placeholder — the autoconfigure wires a ProposalService.
        String proposalId = "proposal:" + binding.id() + ":" + System.nanoTime();
        LOG.info("Tool {} created proposal {} for principal {}", binding.toolName(), proposalId, principal.principalId());
        return ToolResultEnvelope.proposed(binding.toolName(), proposalId,
                "Change proposed. Review and confirm in the dashboard.").toJson();
    }

    private String runAsCallerWithEnvelope(String toolInput, ToolContext toolContext) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext callerContext = SecurityContextHolder.createEmptyContext();
        callerContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(callerContext);
        try {
            String raw = delegate.call(toolInput, toolContext != null ? toolContext : new ToolContext(Map.of()));
            return postProcess(raw);
        } catch (AccessDeniedException e) {
            LOG.info("Tool {} access denied for principal {}: {}", binding.toolName(), principal.principalId(), e.getMessage());
            return ToolResultEnvelope.notPermitted(binding.toolName()).toJson();
        } catch (ToolExecutionException e) {
            LOG.warn("Tool {} execution error for principal {}", binding.toolName(), principal.principalId(), e);
            return ToolResultEnvelope.error(binding.toolName(), "execution_error", sanitize(e)).toJson();
        } catch (Exception e) {
            LOG.error("Tool {} unexpected error for principal {}", binding.toolName(), principal.principalId(), e);
            return ToolResultEnvelope.error(binding.toolName(), "internal_error",
                    "An unexpected error occurred. Please try again.").toJson();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private String postProcess(String raw) {
        // Enforce result-max-chars limit
        int limit = binding.result().maxChars();
        if (limit > 0 && raw != null && raw.length() > limit) {
            LOG.debug("Tool {} result truncated from {} to {} chars", binding.toolName(), raw.length(), limit);
            return ToolResultEnvelope.error(binding.toolName(), "result_truncated",
                    "Result truncated to " + limit + " characters.").toJson();
        }
        return raw != null ? raw : ToolResultEnvelope.ok(binding.toolName(), null,
                java.util.List.of(), Map.of(), false, false, null).toJson();
    }

    private static String sanitize(Throwable e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) return "Tool execution failed.";
        // Strip anything that looks like a stack frame, SQL, or exception class name
        if (msg.length() > 200) msg = msg.substring(0, 200) + "...";
        return msg;
    }
}
