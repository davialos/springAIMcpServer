package com.springaimcpservercommon.ai.tool;

import com.springaimcpservercommon.ai.guard.AiReadScope;
import com.springaimcpservercommon.ai.guard.AiWriteViolationException;
import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.hash.Sha256;
import com.springaimcpservercommon.core.id.Ids;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.Nullable;
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

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Security and compliance decorator around a {@link ToolCallback} (LLD-07 §3, ADR-0008, ADR-0014).
 *
 * <p>On every tool call:
 * <ol>
 *   <li>Reads the {@link InvocationContext} from {@link ToolContext} — never from the model's input JSON.</li>
 *   <li>Re-checks the principal's tool-invoke permission (grants may have changed mid-conversation).</li>
 *   <li>Applies {@link ArgConstraint}s via {@link ArgConstraints}: PRINCIPAL_ATTR and LITERAL values are overwritten
 *       server-side regardless of what the model put in its input, out-of-range values are refused.</li>
 *   <li>Enforces the per-turn call count cap ({@link ToolBinding#maxCallsPerTurn()}).</li>
 *   <li>If {@link WriteMode#PROPOSE}: does NOT invoke the delegate; returns a proposal envelope.</li>
 *   <li>Runs the delegate with the caller's {@link SecurityContext} set on the executing thread, inside the
 *       {@link AiReadScope} (ADR-0014).</li>
 *   <li>Post-processes the result into a {@link ToolResultEnvelope} JSON string.</li>
 * </ol>
 *
 * <p>Every call, whatever its outcome, is reported to the {@link ToolCallRecorder} when a {@link ToolCallScope}
 * was supplied: hashes of arguments and result, status, error code and write-guard veto — never the arguments
 * or the result themselves (F-72). Recording failures are swallowed; they never change what the model receives.
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
    private final ProposalService proposalService;
    private final ToolCallRecorder recorder;
    private final @Nullable ToolCallScope scope;
    private final Clock clock;
    private final ObservationRegistry observations;

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
     * @param proposalService   creates ChangeProposal records for PROPOSE-mode tool calls
     */
    public SecuredToolCallback(ToolCallback delegate, ToolBinding binding, DaiPrincipal principal,
                                Authentication authentication, AtomicInteger sharedCallCount,
                                ToolPermissionChecker permissionChecker,
                                ProposalService proposalService) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.binding = Objects.requireNonNull(binding, "binding");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.callCount = Objects.requireNonNull(sharedCallCount, "sharedCallCount");
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.proposalService = Objects.requireNonNull(proposalService, "proposalService");
        this.recorder = ToolCallRecorder.NOOP;
        this.scope = null;
        this.clock = Clock.systemUTC();
        this.observations = ObservationRegistry.NOOP;
    }

    /**
     * Creates the callback with tool-call recording.
     *
     * @param delegate          the wrapped Spring AI callback
     * @param binding           the governing tool binding
     * @param principal         calling principal
     * @param authentication    Spring Security authentication for the caller
     * @param sharedCallCount   shared counter of calls to this tool within the current turn
     * @param permissionChecker permission checker
     * @param proposalService   creates ChangeProposal records for PROPOSE-mode tool calls
     * @param recorder          receives one record per call
     * @param scope             channel and turn or MCP request the calls belong to; {@code null} disables recording
     * @param clock             time source for call timing
     */
    public SecuredToolCallback(ToolCallback delegate, ToolBinding binding, DaiPrincipal principal,
                                Authentication authentication, AtomicInteger sharedCallCount,
                                ToolPermissionChecker permissionChecker, ProposalService proposalService,
                                ToolCallRecorder recorder, @Nullable ToolCallScope scope, Clock clock) {
        this(delegate, binding, principal, authentication, sharedCallCount, permissionChecker, proposalService,
                recorder, scope, clock, ObservationRegistry.NOOP);
    }

    /**
     * Creates the callback with tool-call recording and tracing.
     *
     * @param delegate          the wrapped Spring AI callback
     * @param binding           the governing tool binding
     * @param principal         calling principal
     * @param authentication    Spring Security authentication for the caller
     * @param sharedCallCount   shared counter of calls to this tool within the current turn
     * @param permissionChecker permission checker
     * @param proposalService   creates ChangeProposal records for PROPOSE-mode tool calls
     * @param recorder          receives one record per call
     * @param scope             channel and turn or MCP request the calls belong to; {@code null} disables recording
     * @param clock             time source for call timing
     * @param observations      registry for the {@code dai.tool} span (the call is measured and traced through it)
     */
    public SecuredToolCallback(ToolCallback delegate, ToolBinding binding, DaiPrincipal principal,
                                Authentication authentication, AtomicInteger sharedCallCount,
                                ToolPermissionChecker permissionChecker, ProposalService proposalService,
                                ToolCallRecorder recorder, @Nullable ToolCallScope scope, Clock clock,
                                ObservationRegistry observations) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.binding = Objects.requireNonNull(binding, "binding");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.callCount = Objects.requireNonNull(sharedCallCount, "sharedCallCount");
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.proposalService = Objects.requireNonNull(proposalService, "proposalService");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.scope = scope;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.observations = Objects.requireNonNull(observations, "observations");
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

    /** What one call produced: the model-facing JSON plus the facts to record. */
    private record Handled(String json, ToolResultStatus status, @Nullable String rawResult, boolean truncated,
                           @Nullable String errorCode, boolean writeViolation, @Nullable UUID proposalId) {

        static Handled of(ToolResultEnvelope envelope, @Nullable String errorCode) {
            return new Handled(envelope.toJson(), envelope.status(), null, false, errorCode, false, null);
        }
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        Instant startedAt = clock.instant();
        UUID invocationId = Ids.newId();
        Observation observation = startObservation();
        try (Observation.Scope ignored = observation.openScope()) {
            Handled handled = handle(toolInput, toolContext, invocationId);
            observation.lowCardinalityKeyValue("dai.tool.status", handled.status().name());
            if (handled.errorCode() != null) {
                observation.lowCardinalityKeyValue("dai.tool.error_code", handled.errorCode());
            }
            if (handled.writeViolation()) {
                observation.lowCardinalityKeyValue("dai.tool.write_violation", "true");
            }
            report(invocationId, startedAt, toolInput, handled);
            return handled.json();
        } catch (RuntimeException e) {
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }

    /**
     * The {@code dai.tool} span (meter {@code dynamic.ai.agent.tool}): tool name, access mode and channel as tags,
     * ids as high-cardinality attributes so a trace can be followed to the store rows. Arguments, results and
     * conversation content are never attached (LLD-10 §3).
     */
    private Observation startObservation() {
        Observation observation = Observation.createNotStarted("dynamic.ai.agent.tool", observations)
                .contextualName("dai.tool")
                .lowCardinalityKeyValue("dai.tool.name", binding.toolName())
                .lowCardinalityKeyValue("dai.tool.access_mode",
                        binding.writeMode() == WriteMode.PROPOSE ? "PROPOSE" : "READ")
                .lowCardinalityKeyValue("dai.channel", scope == null ? "UNKNOWN" : scope.channel().name());
        if (scope != null) {
            if (scope.turnId() != null) {
                observation.highCardinalityKeyValue("dai.turn.id", scope.turnId().toString());
            }
            if (scope.modelCallId() != null) {
                observation.highCardinalityKeyValue("dai.model_call.id", scope.modelCallId().toString());
            }
            if (scope.mcpRequestId() != null) {
                observation.highCardinalityKeyValue("dai.mcp.request.id", scope.mcpRequestId().toString());
            }
            if (scope.parentObservation() != null) {
                observation.parentObservation(scope.parentObservation());
            }
        }
        return observation.start();
    }

    private Handled handle(String toolInput, ToolContext toolContext, UUID invocationId) {
        // 1. Re-check permission (grants may change mid-conversation)
        if (!permissionChecker.isPermitted(principal, binding)) {
            LOG.info("Tool {} denied for principal {}", binding.toolName(), principal.principalId());
            return Handled.of(ToolResultEnvelope.notPermitted(binding.toolName()), "not_permitted");
        }

        // 2. Enforce per-turn call count
        int count = callCount.incrementAndGet();
        if (count > binding.maxCallsPerTurn()) {
            LOG.warn("Tool {} exceeded maxCallsPerTurn({}) for principal {}",
                    binding.toolName(), binding.maxCallsPerTurn(), principal.principalId());
            return Handled.of(ToolResultEnvelope.error(binding.toolName(),
                    "call_limit_exceeded",
                    "Tool call limit (" + binding.maxCallsPerTurn() + " per turn) exceeded."), "call_limit_exceeded");
        }

        // 3. Server-decided arguments: the model never chooses them
        ArgConstraints.Result constrained = ArgConstraints.apply(binding.argConstraints(), principal, toolInput);
        if (constrained instanceof ArgConstraints.Rejected rejected) {
            LOG.info("Tool {} refused by argument constraint {} for principal {}", binding.toolName(),
                    rejected.code(), principal.principalId());
            return Handled.of(ToolResultEnvelope.error(binding.toolName(), rejected.code(), rejected.message()),
                    rejected.code());
        }
        String effectiveInput = ((ArgConstraints.Applied) constrained).input();

        // 4. If PROPOSE → create proposal, do not run the delegate
        if (binding.writeMode() == WriteMode.PROPOSE) {
            return handleProposal(effectiveInput, invocationId);
        }

        // 5. Run delegate as the caller with the correct SecurityContext
        return runAsCallerWithEnvelope(effectiveInput, toolContext);
    }

    private Handled handleProposal(String toolInput, UUID invocationId) {
        if (scope == null) {
            LOG.warn("Tool {} proposes a change without a call scope; refused", binding.toolName());
            return Handled.of(ToolResultEnvelope.error(binding.toolName(), "proposal_unavailable",
                    "The change could not be proposed. Try again later."), "proposal_unavailable");
        }
        java.util.UUID proposalId;
        try {
            proposalId = proposalService.createProposal(new ProposalService.ProposalRequest(binding, toolInput,
                    principal, scope, invocationId));
        } catch (ProposalService.ProposalRefusedException e) {
            LOG.info("Tool {} proposal refused for principal {}: {}", binding.toolName(), principal.principalId(),
                    e.code());
            return Handled.of(ToolResultEnvelope.error(binding.toolName(), e.code(), e.getMessage()), e.code());
        } catch (RuntimeException e) {
            LOG.warn("Tool {} could not create a proposal for principal {} ({})", binding.toolName(),
                    principal.principalId(), e.getClass().getSimpleName());
            return Handled.of(ToolResultEnvelope.error(binding.toolName(), "proposal_unavailable",
                    "The change could not be proposed. Try again later."), "proposal_unavailable");
        }
        LOG.info("Tool {} created proposal {} for principal {}",
                binding.toolName(), proposalId, principal.principalId());
        ToolResultEnvelope envelope = ToolResultEnvelope.proposed(binding.toolName(), proposalId.toString(),
                "Change proposed. Review and confirm in the dashboard.");
        return new Handled(envelope.toJson(), ToolResultStatus.PROPOSED, null, false, null, false, proposalId);
    }

    private Handled runAsCallerWithEnvelope(String toolInput, ToolContext toolContext) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext callerContext = SecurityContextHolder.createEmptyContext();
        callerContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(callerContext);
        try {
            ToolContext context = toolContext != null ? toolContext : new ToolContext(Map.of());
            // Tools that execute (as opposed to PROPOSE) are read tools: run them in the AI read scope so the write
            // guard vetoes any entity mutation (ADR-0014).
            String raw = AiReadScope.callScoped(() -> delegate.call(toolInput, context));
            return postProcess(raw);
        } catch (AccessDeniedException e) {
            LOG.info("Tool {} access denied for principal {}: {}", binding.toolName(), principal.principalId(), e.getMessage());
            return Handled.of(ToolResultEnvelope.notPermitted(binding.toolName()), "not_permitted");
        } catch (ToolExecutionException e) {
            LOG.warn("Tool {} execution error for principal {}", binding.toolName(), principal.principalId(), e);
            boolean violation = isWriteViolation(e);
            return failure(ToolResultEnvelope.error(binding.toolName(), "execution_error", sanitize(e)),
                    violation ? "write_violation" : "execution_error", violation);
        } catch (Exception e) {
            LOG.error("Tool {} unexpected error for principal {}", binding.toolName(), principal.principalId(), e);
            boolean violation = isWriteViolation(e);
            return failure(ToolResultEnvelope.error(binding.toolName(), "internal_error",
                    "An unexpected error occurred. Please try again."),
                    violation ? "write_violation" : "internal_error", violation);
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private static Handled failure(ToolResultEnvelope envelope, String errorCode, boolean writeViolation) {
        return new Handled(envelope.toJson(), envelope.status(), null, false, errorCode, writeViolation, null);
    }

    private static boolean isWriteViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof AiWriteViolationException) {
                return true;
            }
        }
        return false;
    }

    private Handled postProcess(@Nullable String raw) {
        // Enforce result-max-chars limit
        int limit = binding.result().maxChars();
        if (limit > 0 && raw != null && raw.length() > limit) {
            LOG.debug("Tool {} result truncated from {} to {} chars", binding.toolName(), raw.length(), limit);
            String json = ToolResultEnvelope.error(binding.toolName(), "result_truncated",
                    "Result truncated to " + limit + " characters.").toJson();
            return new Handled(json, ToolResultStatus.TRUNCATED, raw, true, "result_truncated", false, null);
        }
        if (raw == null) {
            String json = ToolResultEnvelope.ok(binding.toolName(), null,
                    java.util.List.of(), Map.of(), false, false, null).toJson();
            return new Handled(json, ToolResultStatus.EMPTY, null, false, null, false, null);
        }
        return new Handled(raw, raw.isBlank() ? ToolResultStatus.EMPTY : ToolResultStatus.OK, raw, false, null,
                false, null);
    }

    /** Reports the call; never throws and never changes the result the model receives. */
    private void report(UUID invocationId, Instant startedAt, @Nullable String toolInput, Handled handled) {
        ToolCallScope callScope = scope;
        if (callScope == null) {
            return;
        }
        try {
            recorder.record(new ToolCallRecorder.ToolCall(invocationId, startedAt, clock.instant(), callScope,
                    binding, principal.principalId(), elementRef(binding.source()),
                    Sha256.of(toolInput == null ? "" : toolInput), handled.status(),
                    handled.rawResult() == null ? null : Sha256.of(handled.rawResult()), handled.truncated(),
                    handled.errorCode(), handled.writeViolation(), handled.proposalId()));
        } catch (RuntimeException e) {
            LOG.warn("Recording of tool {} failed ({}); the call is unaffected", binding.toolName(),
                    e.getClass().getSimpleName());
        }
    }

    /** The catalog element a binding's source points at, in the form the store accepts. */
    static CatalogElementRef elementRef(ToolSource source) {
        return switch (source) {
            case ToolSource.OperationSource s -> s.opRef();
            case ToolSource.QuerySource s -> new CatalogElementRef(CatalogElementRef.Kind.QUERY, s.queryId().toString());
            case ToolSource.AgentSource s -> new CatalogElementRef(CatalogElementRef.Kind.AGENT, s.agentId().toString());
            case ToolSource.McpSource s -> new CatalogElementRef(CatalogElementRef.Kind.MCP,
                    safeValue(s.serverId() + "/" + s.remoteTool()));
        };
    }

    private static String safeValue(String text) {
        String cleaned = text.replaceAll("[^A-Za-z0-9_$.#()\\[\\],/<>?-]", "_");
        return cleaned.length() > 1000 ? cleaned.substring(0, 1000) : cleaned;
    }

    private static String sanitize(Throwable e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) return "Tool execution failed.";
        // Strip anything that looks like a stack frame, SQL, or exception class name
        if (msg.length() > 200) msg = msg.substring(0, 200) + "...";
        return msg;
    }
}
