package com.springaimcpservercommon.ai.runtime;

import com.springaimcpservercommon.ai.advisor.InvocationGuardAdvisor;
import com.springaimcpservercommon.ai.advisor.JsonSchemaValidationPort;
import com.springaimcpservercommon.ai.advisor.StructuredOutputValidationAdvisor;
import com.springaimcpservercommon.ai.advisor.SummaryMemoryAdvisor;
import com.springaimcpservercommon.ai.advisor.UsageMeteringAdvisor;
import com.springaimcpservercommon.ai.knowledge.KnowledgeAdvisor;
import com.springaimcpservercommon.ai.knowledge.KnowledgeStore;
import com.springaimcpservercommon.ai.agent.AgentDefinition;
import com.springaimcpservercommon.ai.agent.MemorySpec;
import com.springaimcpservercommon.ai.agent.OutputSpec;
import com.springaimcpservercommon.ai.agent.ModelSelection;
import com.springaimcpservercommon.ai.model.ModelRouter;
import com.springaimcpservercommon.ai.model.ResolvedModel;
import com.springaimcpservercommon.ai.safety.TurnSafety;
import com.springaimcpservercommon.ai.agent.ChatUiSpec;
import com.springaimcpservercommon.ai.chat.ChatUiRuntime;
import com.springaimcpservercommon.ai.chat.ChatUiState;
import com.springaimcpservercommon.ai.chat.Choice;
import com.springaimcpservercommon.ai.chat.PresentChoicesTool;
import com.springaimcpservercommon.core.hash.Sha256;
import com.springaimcpservercommon.core.display.StructuredResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import com.springaimcpservercommon.ai.tool.ToolBridge;
import com.springaimcpservercommon.ai.tool.ToolCallScope;
import com.springaimcpservercommon.core.catalog.MetadataRegistry;
import com.springaimcpservercommon.core.id.Ids;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.Ordered;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Instant;
import java.time.Duration;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default {@link AgentInvoker}: assembles a per-turn {@link ChatClient} with kill-switch,
 * memory, structured-output and metering advisors, then dispatches sync or streaming turns
 * (LLD-06 §3, §4).
 *
 * <p>Advisor order (outside the Spring AI tool-calling loop at HIGHEST_PRECEDENCE + 300):
 * <ol>
 *   <li>{@link InvocationGuardAdvisor} at {@code HIGHEST_PRECEDENCE + 200} — kill-switch, guardrails.</li>
 *   <li>{@link MessageChatMemoryAdvisor} / {@link SummaryMemoryAdvisor} at {@code HIGHEST_PRECEDENCE + 201} — history injection (WINDOW / SUMMARY strategy respectively).</li>
 *   <li>{@link StructuredOutputValidationAdvisor} at {@code LOWEST_PRECEDENCE - 100} — JSON validation.</li>
 *   <li>{@link UsageMeteringAdvisor} at {@code LOWEST_PRECEDENCE} — token usage accounting.</li>
 * </ol>
 * {@code ToolCallingAdvisor} is auto-registered by Spring AI at {@code HIGHEST_PRECEDENCE + 300}.
 *
 * <p>Guardrails ({@link TurnSafety}, LLD-06 §8): the prompt is validated (malicious content, business scope, host
 * validators) and optionally PII-redacted before the model sees it; the answer is PII-redacted (chunk-safe on
 * streams), cut at {@code maxOutputChars}, and accompanied by a structured display ({@link StructuredResponse}: the
 * sync result's {@code display}, a {@code ui.component} event of type {@value #STRUCTURED_RESPONSE} on streams).
 *
 * <p>Not a Spring {@code @Component} — registered by {@code DaiAiAutoConfiguration} when a
 * {@link ModelRouter} and {@link MetadataRegistry} bean are present.
 */
@NullMarked
public final class DefaultAgentInvoker implements AgentInvoker {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultAgentInvoker.class);
    private static final int MEMORY_ORDER = Ordered.HIGHEST_PRECEDENCE + 201;
    /** Cap on the answer text kept in memory for a streamed turn that is being recorded. */
    private static final int MAX_RECORDED_ANSWER_CHARS = 200_000;
    /**
     * Reactor-context key under which Micrometer and Spring AI look up the parent observation
     * ({@code ObservationThreadLocalAccessor.KEY}; written as a literal so the ai module needs no compile dependency
     * on context-propagation).
     */
    static final String OBSERVATION_CONTEXT_KEY = "micrometer.observation";
    /** Largest structured answer held back for validation on a stream; more fails the turn. */
    private static final int MAX_STRUCTURED_ANSWER_CHARS = 1_000_000;
    /** {@code componentType} of the stream event carrying the structured display. */
    public static final String STRUCTURED_RESPONSE = "structured-response";

    private final ModelRouter modelRouter;
    private final @Nullable ToolBridge toolBridge;
    private final MetadataRegistry metadataRegistry;
    private final @Nullable KnowledgeStore knowledgeStore;
    private final int knowledgeMaxChars;
    private final InvocationGuardAdvisor.KillSwitchChecker killSwitchChecker;
    private final InvocationGuardAdvisor.BudgetChecker budgetChecker;
    private final UsageMeteringAdvisor.UsageSink usageSink;
    private final TurnRecorder turnRecorder;
    private final ConversationRecorder conversationRecorder;
    private final Clock clock = Clock.systemUTC();
    private final ObservationRegistry observationRegistry;
    private final ChatMemory chatMemory;
    private final @Nullable JsonSchemaValidationPort schemaValidator;
    private final TurnSafety turnSafety;
    private final ChatUiRuntime chatUi;

    /**
     * Creates the invoker.
     *
     * @param modelRouter          resolves the {@link ChatModel} for each turn
     * @param toolBridge           assembles per-request tool callbacks; {@code null} if tools are unavailable
     * @param metadataRegistry     current effective catalog snapshot
     * @param killSwitchChecker    runtime kill-switch check
     * @param budgetChecker        token-budget pre-check
     * @param usageSink            token usage accounting
     * @param turnRecorder         receives one record per finished turn (trace viewer, F-72)
     * @param conversationRecorder receives the user message and answer of each successful turn (F-44)
     * @param observationRegistry  Micrometer observation registry
     * @param chatMemory           conversation history store
     * @param schemaValidator      optional JSON Schema conformance validator (Level 2);
     *                             when {@code null} only well-formedness is enforced
     */
    public DefaultAgentInvoker(ModelRouter modelRouter,
                                @Nullable ToolBridge toolBridge,
                                MetadataRegistry metadataRegistry,
                                InvocationGuardAdvisor.KillSwitchChecker killSwitchChecker,
                                InvocationGuardAdvisor.BudgetChecker budgetChecker,
                                UsageMeteringAdvisor.UsageSink usageSink,
                                TurnRecorder turnRecorder,
                                ConversationRecorder conversationRecorder,
                                ObservationRegistry observationRegistry,
                                ChatMemory chatMemory,
                                @Nullable JsonSchemaValidationPort schemaValidator) {
        this(modelRouter, toolBridge, metadataRegistry, killSwitchChecker, budgetChecker, usageSink, turnRecorder,
                conversationRecorder, observationRegistry, chatMemory, schemaValidator, TurnSafety.disabled(), null, 0);
    }

    /**
     * Creates the invoker with guardrails.
     *
     * @param modelRouter          resolves the {@link ChatModel} for each turn
     * @param toolBridge           assembles per-request tool callbacks; {@code null} if tools are unavailable
     * @param metadataRegistry     current effective catalog snapshot
     * @param killSwitchChecker    runtime kill-switch check
     * @param budgetChecker        token-budget pre-check
     * @param usageSink            token usage accounting
     * @param turnRecorder         receives one record per finished turn (trace viewer, F-72)
     * @param conversationRecorder receives the user message and answer of each successful turn (F-44)
     * @param observationRegistry  Micrometer observation registry
     * @param chatMemory           conversation history store
     * @param schemaValidator      optional JSON Schema conformance validator (Level 2)
     * @param turnSafety           prompt validation, PII redaction and structured display (F-76)
     */
    public DefaultAgentInvoker(ModelRouter modelRouter,
                                @Nullable ToolBridge toolBridge,
                                MetadataRegistry metadataRegistry,
                                InvocationGuardAdvisor.KillSwitchChecker killSwitchChecker,
                                InvocationGuardAdvisor.BudgetChecker budgetChecker,
                                UsageMeteringAdvisor.UsageSink usageSink,
                                TurnRecorder turnRecorder,
                                ConversationRecorder conversationRecorder,
                                ObservationRegistry observationRegistry,
                                ChatMemory chatMemory,
                                @Nullable JsonSchemaValidationPort schemaValidator,
                                TurnSafety turnSafety) {
        this(modelRouter, toolBridge, metadataRegistry, killSwitchChecker, budgetChecker, usageSink, turnRecorder,
                conversationRecorder, observationRegistry, chatMemory, schemaValidator, turnSafety, null, 0);
    }

    /**
     * Same, with the knowledge packs agents may draw context from (guardrails disabled).
     *
     * @param modelRouter          resolves the {@link ChatModel} for each turn
     * @param toolBridge           assembles per-request tool callbacks; {@code null} if tools are unavailable
     * @param metadataRegistry     current effective catalog snapshot
     * @param killSwitchChecker    runtime kill-switch check
     * @param budgetChecker        token-budget pre-check
     * @param usageSink            token usage accounting
     * @param turnRecorder         receives one record per finished turn (trace viewer, F-72)
     * @param conversationRecorder receives the user message and answer of each successful turn (F-44)
     * @param observationRegistry  Micrometer observation registry
     * @param chatMemory           conversation history store
     * @param schemaValidator      optional JSON Schema conformance validator (Level 2)
     * @param knowledgeStore       the bundled knowledge packs, or {@code null} to give agents none
     * @param knowledgeMaxChars    most characters of retrieved text added to one prompt
     */
    public DefaultAgentInvoker(ModelRouter modelRouter,
                                @Nullable ToolBridge toolBridge,
                                MetadataRegistry metadataRegistry,
                                InvocationGuardAdvisor.KillSwitchChecker killSwitchChecker,
                                InvocationGuardAdvisor.BudgetChecker budgetChecker,
                                UsageMeteringAdvisor.UsageSink usageSink,
                                TurnRecorder turnRecorder,
                                ConversationRecorder conversationRecorder,
                                ObservationRegistry observationRegistry,
                                ChatMemory chatMemory,
                                @Nullable JsonSchemaValidationPort schemaValidator,
                                @Nullable KnowledgeStore knowledgeStore,
                                int knowledgeMaxChars) {
        this(modelRouter, toolBridge, metadataRegistry, killSwitchChecker, budgetChecker, usageSink, turnRecorder,
                conversationRecorder, observationRegistry, chatMemory, schemaValidator, TurnSafety.disabled(),
                knowledgeStore, knowledgeMaxChars);
    }

    /**
     * Creates the invoker with guardrails and knowledge packs.
     *
     * @param modelRouter          resolves the {@link ChatModel} for each turn
     * @param toolBridge           assembles per-request tool callbacks; {@code null} if tools are unavailable
     * @param metadataRegistry     current effective catalog snapshot
     * @param killSwitchChecker    runtime kill-switch check
     * @param budgetChecker        token-budget pre-check
     * @param usageSink            token usage accounting
     * @param turnRecorder         receives one record per finished turn (trace viewer, F-72)
     * @param conversationRecorder receives the user message and answer of each successful turn (F-44)
     * @param observationRegistry  Micrometer observation registry
     * @param chatMemory           conversation history store
     * @param schemaValidator      optional JSON Schema conformance validator (Level 2)
     * @param turnSafety           prompt validation, PII redaction and structured display (F-76)
     * @param knowledgeStore       the bundled knowledge packs, or {@code null} to give agents none
     * @param knowledgeMaxChars    most characters of retrieved text added to one prompt
     */
    public DefaultAgentInvoker(ModelRouter modelRouter,
                                @Nullable ToolBridge toolBridge,
                                MetadataRegistry metadataRegistry,
                                InvocationGuardAdvisor.KillSwitchChecker killSwitchChecker,
                                InvocationGuardAdvisor.BudgetChecker budgetChecker,
                                UsageMeteringAdvisor.UsageSink usageSink,
                                TurnRecorder turnRecorder,
                                ConversationRecorder conversationRecorder,
                                ObservationRegistry observationRegistry,
                                ChatMemory chatMemory,
                                @Nullable JsonSchemaValidationPort schemaValidator,
                                TurnSafety turnSafety,
                                @Nullable KnowledgeStore knowledgeStore,
                                int knowledgeMaxChars) {
        this(modelRouter, toolBridge, metadataRegistry, killSwitchChecker, budgetChecker, usageSink, turnRecorder,
                conversationRecorder, observationRegistry, chatMemory, schemaValidator, turnSafety, knowledgeStore,
                knowledgeMaxChars, ChatUiRuntime.OFF);
    }

    /**
     * Creates the invoker with guardrails, knowledge packs and chat-interface features.
     *
     * @param modelRouter          resolves the {@link ChatModel} for each turn
     * @param toolBridge           assembles per-request tool callbacks; {@code null} if tools are unavailable
     * @param metadataRegistry     current effective catalog snapshot
     * @param killSwitchChecker    runtime kill-switch check
     * @param budgetChecker        token-budget pre-check
     * @param usageSink            token usage accounting
     * @param turnRecorder         receives one record per finished turn (trace viewer, F-72)
     * @param conversationRecorder receives the user message and answer of each successful turn (F-44)
     * @param observationRegistry  Micrometer observation registry
     * @param chatMemory           conversation history store
     * @param schemaValidator      optional JSON Schema conformance validator (Level 2)
     * @param turnSafety           prompt validation, PII redaction and structured display (F-76)
     * @param knowledgeStore       the bundled knowledge packs, or {@code null} to give agents none
     * @param knowledgeMaxChars    most characters of retrieved text added to one prompt
     * @param chatUi               chat-interface defaults and state: step details, choices, feedback flags
     *                             (LLD-13 §3)
     */
    public DefaultAgentInvoker(ModelRouter modelRouter,
                                @Nullable ToolBridge toolBridge,
                                MetadataRegistry metadataRegistry,
                                InvocationGuardAdvisor.KillSwitchChecker killSwitchChecker,
                                InvocationGuardAdvisor.BudgetChecker budgetChecker,
                                UsageMeteringAdvisor.UsageSink usageSink,
                                TurnRecorder turnRecorder,
                                ConversationRecorder conversationRecorder,
                                ObservationRegistry observationRegistry,
                                ChatMemory chatMemory,
                                @Nullable JsonSchemaValidationPort schemaValidator,
                                TurnSafety turnSafety,
                                @Nullable KnowledgeStore knowledgeStore,
                                int knowledgeMaxChars,
                                ChatUiRuntime chatUi) {
        this.chatUi = Objects.requireNonNull(chatUi, "chatUi");
        this.turnSafety = Objects.requireNonNull(turnSafety, "turnSafety");
        this.knowledgeStore = knowledgeStore;
        this.knowledgeMaxChars = knowledgeMaxChars;
        this.modelRouter = Objects.requireNonNull(modelRouter, "modelRouter");
        this.toolBridge = toolBridge;
        this.metadataRegistry = Objects.requireNonNull(metadataRegistry, "metadataRegistry");
        this.killSwitchChecker = Objects.requireNonNull(killSwitchChecker, "killSwitchChecker");
        this.budgetChecker = Objects.requireNonNull(budgetChecker, "budgetChecker");
        this.usageSink = Objects.requireNonNull(usageSink, "usageSink");
        this.turnRecorder = Objects.requireNonNull(turnRecorder, "turnRecorder");
        this.conversationRecorder = Objects.requireNonNull(conversationRecorder, "conversationRecorder");
        this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry");
        this.chatMemory = Objects.requireNonNull(chatMemory, "chatMemory");
        this.schemaValidator = schemaValidator;
    }

    // ─── Sync turn ────────────────────────────────────────────────────────────

    @Override
    public SyncChatResult invoke(AgentDefinition agent, AgentChatRequest request,
                                  DaiPrincipal principal, Authentication authentication) {
        UUID conversationId = request.conversationId() != null ? request.conversationId() : Ids.newId();
        UUID turnId = request.turnId() != null ? request.turnId() : Ids.newId();
        UUID modelCallId = Ids.newId();
        LOG.debug("Agent {} sync turn {} for principal {}", agent.slug(), turnId, principal.principalId());
        Instant startedAt = clock.instant();
        Observation turnObservation = startTurnObservation(agent, request, turnId, modelCallId, false);
        // open for the whole synchronous turn so spans below it (model call, tools, queries) nest under it
        Observation.Scope turnScope = turnObservation.openScope();
        String traceId = currentTraceId();

        try {
            if (!budgetChecker.hasRemainingBudget(agent, principal)) {
                LOG.warn("Agent {} budget exhausted for principal {}; sync turn {} rejected",
                        agent.slug(), principal.principalId(), turnId);
                throw new AgentInvocationException("budget-exhausted",
                        "The usage budget for this agent is exhausted for the current period.", false);
            }
            ResolvedModel resolved = modelRouter.resolveModel(agent.model(), principal);
            ChatModel chatModel = resolved.model();
            String convKey = convKey(principal, agent, conversationId);
            TurnEvents sideEvents = new TurnEvents(false);
            List<ToolCallback> callbacks = withChatUi(agent, principal, turnId, convKey, sideEvents,
                    buildToolCallbacks(agent, principal, authentication, request, turnId, modelCallId, turnObservation));
            ChatClient client = buildChatClient(agent, principal, resolved);
            // the model and the chat memory only ever see the prompt after input redaction; validation judges the raw one
            String userText = turnSafety.prepareInput(agent, request.message());

            ChatResponse response = client.prompt()
                    .user(userText)
                    .toolCallbacks(callbacks)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, convKey)
                            .param(InvocationGuardAdvisor.RAW_INPUT_KEY, request.message()))
                    .call()
                    .chatResponse();

            if (response == null) {
                throw new AgentInvocationException("empty-response",
                        "Agent returned no response.", true);
            }
            SyncChatResult result = withSideEvents(guardSyncResult(agent, mapSyncResult(response, conversationId, turnId)),
                    sideEvents.collected());
            recordTurn(agent, request, principal, turnId, modelCallId, turnObservation, conversationId, startedAt, traceId, false,
                    TurnRecorder.Outcome.SUCCESS, TurnRecorder.Finish.STOP, null, null,
                    result.usage().inputTokens(), result.usage().outputTokens());
            recordExchange(agent, request, principal, turnId, conversationId, startedAt, userText, result.message());
            return result;
        } catch (AgentInvocationException e) {
            boolean budget = "budget-exhausted".equals(e.code());
            recordTurn(agent, request, principal, turnId, modelCallId, turnObservation, conversationId, startedAt, traceId, false,
                    budget ? TurnRecorder.Outcome.REJECTED : TurnRecorder.Outcome.FAILED,
                    budget ? TurnRecorder.Finish.BUDGET : TurnRecorder.Finish.ERROR, e.code(), null, 0, 0);
            throw e;
        } catch (Exception e) {
            LOG.error("Agent {} sync turn {} failed for principal {}",
                    agent.slug(), turnId, principal.principalId(), e);
            recordTurn(agent, request, principal, turnId, modelCallId, turnObservation, conversationId, startedAt, traceId, false,
                    TurnRecorder.Outcome.FAILED, TurnRecorder.Finish.ERROR, "execution-error", null, 0, 0);
            throw new AgentInvocationException("execution-error", "Agent invocation failed.", true);
        } finally {
            turnScope.close();
        }
    }

    // ─── Streaming turn ───────────────────────────────────────────────────────

    @Override
    public Flux<StreamEvent> stream(AgentDefinition agent, AgentChatRequest request,
                                     DaiPrincipal principal, Authentication authentication) {
        UUID conversationId = request.conversationId() != null ? request.conversationId() : Ids.newId();
        UUID turnId = request.turnId() != null ? request.turnId() : Ids.newId();
        UUID modelCallId = Ids.newId();

        return Flux.defer(() -> {
            LOG.debug("Agent {} stream turn {} for principal {}",
                    agent.slug(), turnId, principal.principalId());
            Instant startedAt = clock.instant();
            Observation turnObservation = startTurnObservation(agent, request, turnId, modelCallId, true);
            String traceId;
            try (Observation.Scope ignored = turnObservation.openScope()) {
                traceId = currentTraceId();
            }
            try {
                // Streamed turns skip the call-advisor chain, so the input checks run here (LLD-06 §4)
                var violation = new InvocationGuardAdvisor(agent, principal, killSwitchChecker, budgetChecker, turnSafety)
                        .checkInput(request.message());
                if (violation.isPresent()) {
                    String code = violation.get().code().replace('_', '-');
                    LOG.info("Agent {} stream turn {} rejected by guard ({})", agent.slug(), turnId, code);
                    recordTurn(agent, request, principal, turnId, modelCallId, turnObservation, conversationId, startedAt, traceId, true,
                            TurnRecorder.Outcome.REJECTED, TurnRecorder.Finish.ERROR, code, null, 0, 0);
                    return Flux.just(new StreamEvent.ErrorEvent(
                            "/errors/agent/" + code, violation.get().message(), code, false, turnId));
                }
                if (!budgetChecker.hasRemainingBudget(agent, principal)) {
                    LOG.warn("Agent {} budget exhausted for principal {}; stream turn {} rejected",
                            agent.slug(), principal.principalId(), turnId);
                    recordTurn(agent, request, principal, turnId, modelCallId, turnObservation, conversationId, startedAt, traceId, true,
                            TurnRecorder.Outcome.REJECTED, TurnRecorder.Finish.BUDGET, "budget-exhausted",
                            null, 0, 0);
                    return Flux.just(new StreamEvent.ErrorEvent(
                            "/errors/agent/budget-exhausted", "Usage limit reached",
                            "budget-exhausted", false, turnId));
                }
                ResolvedModel resolved = modelRouter.resolveModel(agent.model(), principal);
                ChatModel chatModel = resolved.model();
                String convKey = convKey(principal, agent, conversationId);
                ChatUiSpec ui = chatUi.effective(agent);
                TurnEvents sideEvents = new TurnEvents(true);
                List<ToolCallback> callbacks = withChatUi(agent, principal, turnId, convKey, sideEvents,
                        buildToolCallbacks(agent, principal, authentication, request, turnId, modelCallId, turnObservation));
                ChatClient client = buildChatClient(agent, principal, resolved);
                String userText = turnSafety.prepareInput(agent, request.message());
                TurnSafety.OutputGuard outputGuard = turnSafety.outputGuard(agent);
                StringBuilder rawAnswer = new StringBuilder();

                AtomicInteger seq = new AtomicInteger(0);
                AtomicReference<@Nullable ChatResponse> lastResponse = new AtomicReference<>();
                AtomicReference<@Nullable Instant> firstText = new AtomicReference<>();
                AtomicReference<@Nullable String> failure = new AtomicReference<>();
                StringBuilder answer = new StringBuilder();
                // JSON_SCHEMA agents: a partial document is not usable and cannot be validated, so the answer is held
                // back, validated when the stream ends and then sent whole (or refused) (OQ-51)
                boolean structured = agent.output().mode() == OutputSpec.Mode.JSON_SCHEMA;
                StringBuilder held = new StringBuilder();
                AtomicBoolean heldTooLarge = new AtomicBoolean(false);

                Flux<StreamEvent> content = client.prompt()
                        .user(userText)
                        .toolCallbacks(callbacks)
                        // the input checks above already ran; the stream advisor must not run them a second time
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, convKey)
                                .param(InvocationGuardAdvisor.PRECHECKED_KEY, Boolean.TRUE))
                        .stream()
                        .chatResponse()
                        // A stream has no stable thread, so its span can't be current in a ThreadLocal. Spring AI's
                        // stream path reads its parent from the Reactor context under this key instead (OQ-50).
                        .contextWrite(ctx -> ctx.put(OBSERVATION_CONTEXT_KEY, turnObservation))
                        .timeout(agent.limits().turnTimeout())
                        .doOnNext(lastResponse::set)
                        .flatMapIterable(r -> {
                            String text = extractText(r);
                            if (text.isEmpty()) {
                                return List.<StreamEvent>of();
                            }
                            firstText.compareAndSet(null, clock.instant());
                            if (structured) {
                                if (held.length() + text.length() > MAX_STRUCTURED_ANSWER_CHARS) {
                                    heldTooLarge.set(true);
                                } else {
                                    held.append(text);
                                }
                                return List.<StreamEvent>of();
                            }
                            if (rawAnswer.length() < MAX_RECORDED_ANSWER_CHARS) {
                                rawAnswer.append(text);
                            }
                            // redaction holds back text that could be the start of personal data (chunk-safe)
                            String safe = outputGuard.onChunk(text);
                            if (safe.isEmpty()) {
                                return List.<StreamEvent>of();
                            }
                            appendRecorded(answer, safe);
                            return List.<StreamEvent>of(new StreamEvent.TextDelta(seq.incrementAndGet(), safe));
                        });

                Flux<StreamEvent> ending = Flux.defer(() -> {
                    ChatResponse last = lastResponse.get();
                    recordStreamUsage(agent, principal, last);
                    if (structured) {
                        String text = held.toString();
                        var failed = heldTooLarge.get()
                                ? java.util.Optional.of(new StructuredOutputValidationAdvisor.Failure(
                                        "output_too_large", "The agent response is too large."))
                                : new StructuredOutputValidationAdvisor(agent.output(), schemaValidator).check(text);
                        if (failed.isPresent()) {
                            String code = failed.get().code().replace('_', '-');
                            failure.set(code);
                            return Flux.just(new StreamEvent.ErrorEvent("/errors/agent/" + code,
                                    failed.get().message(), code, false, turnId));
                        }
                        String safe = outputGuard.finish(text);
                        appendRecorded(answer, safe);
                        List<StreamEvent> events = new ArrayList<>();
                        events.add(new StreamEvent.TextDelta(seq.incrementAndGet(), safe));
                        addDisplay(events, outputGuard.display(text));
                        events.addAll(buildEndingEvents(last, agent, turnId));
                        return Flux.fromIterable(events);
                    }
                    List<StreamEvent> events = new ArrayList<>();
                    String tail = outputGuard.finishStream();
                    if (!tail.isEmpty()) {
                        appendRecorded(answer, tail);
                        events.add(new StreamEvent.TextDelta(seq.incrementAndGet(), tail));
                    }
                    addDisplay(events, outputGuard.display(rawAnswer.toString()));
                    events.addAll(buildEndingEvents(last, agent, turnId));
                    return Flux.fromIterable(events);
                });

                List<StreamEvent> opening = new ArrayList<>(2);
                opening.add(new StreamEvent.TurnStart(turnId, conversationId, agent.slug(), agent.revision(),
                        StreamEvent.TurnStart.PROTOCOL, ui));
                if (ui != null && ui.steps()) {
                    // the input checks have passed by now, so this step is known before the model starts
                    opening.add(new StreamEvent.Step("check", "Checked your request", "done", null));
                }

                // step, tool and component events from tool threads join the text stream as they happen; the side
                // channel closes when the model's stream does, so usage and turn.end always come last
                Flux<StreamEvent> live = Flux.merge(sideEvents.flux(),
                        content.doFinally(signal -> sideEvents.complete()));

                AtomicBoolean recorded = new AtomicBoolean(false);
                return Flux.concat(
                        Flux.fromIterable(opening),
                        live.concatWith(ending)
                ).onErrorResume(e -> {
                    LOG.error("Agent {} stream error for principal {}",
                            agent.slug(), principal.principalId(), e);
                    String code = errorCode(e);
                    failure.set(code);
                    return Flux.just(new StreamEvent.ErrorEvent(
                            "/errors/agent/" + code, "Agent stream error",
                            code, isRetryable(e), turnId));
                }).doFinally(signal -> {
                    if (!recorded.compareAndSet(false, true)) {
                        return;
                    }
                    long[] usage = usageOf(lastResponse.get());
                    Instant first = firstText.get();
                    Integer ttft = first == null ? null : (int) Math.min(Integer.MAX_VALUE,
                            Duration.between(startedAt, first).toMillis());
                    String code = failure.get();
                    if (signal == reactor.core.publisher.SignalType.CANCEL && code == null) {
                        recordTurn(agent, request, principal, turnId, modelCallId, turnObservation, conversationId, startedAt, traceId, true,
                                TurnRecorder.Outcome.CANCELLED, TurnRecorder.Finish.CANCELLED, "client-cancelled",
                                ttft, usage[0], usage[1]);
                    } else if (code != null) {
                        recordTurn(agent, request, principal, turnId, modelCallId, turnObservation, conversationId, startedAt, traceId, true,
                                TurnRecorder.Outcome.FAILED, TurnRecorder.Finish.ERROR, code, ttft,
                                usage[0], usage[1]);
                    } else {
                        recordTurn(agent, request, principal, turnId, modelCallId, turnObservation, conversationId, startedAt, traceId, true,
                                TurnRecorder.Outcome.SUCCESS, TurnRecorder.Finish.STOP, null, ttft,
                                usage[0], usage[1]);
                        String text;
                        synchronized (answer) {
                            text = answer.toString();
                        }
                        recordExchange(agent, request, principal, turnId, conversationId, startedAt, userText, text);
                    }
                });
            } catch (Exception e) {
                LOG.error("Agent {} stream setup failed for principal {}",
                        agent.slug(), principal.principalId(), e);
                recordTurn(agent, request, principal, turnId, modelCallId, turnObservation, conversationId, startedAt, traceId, true,
                        TurnRecorder.Outcome.FAILED, TurnRecorder.Finish.ERROR, "stream-error", null, 0, 0);
                return Flux.just(new StreamEvent.ErrorEvent(
                        "/errors/agent/stream-error", "Stream setup failed",
                        "stream-error", true, turnId));
            }
        });
    }

    // ─── Tracing ──────────────────────────────────────────────────────────────

    /**
     * Starts the {@code dai.agent.turn} span (meter {@code dynamic.ai.agent.turn}, LLD-10 §3): agent, channel and
     * streaming as tags, turn and model call ids as high-cardinality attributes so a trace can be followed to the
     * store rows. No prompt, answer or tool content is attached.
     */
    private Observation startTurnObservation(AgentDefinition agent, AgentChatRequest request, UUID turnId,
                                             UUID modelCallId, boolean streaming) {
        return Observation.createNotStarted("dynamic.ai.agent.turn", observationRegistry)
                .contextualName("dai.agent.turn")
                .lowCardinalityKeyValue("dai.agent.slug", agent.slug())
                .lowCardinalityKeyValue("dai.channel", request.effectiveChannel().name())
                .lowCardinalityKeyValue("dai.streaming", String.valueOf(streaming))
                .highCardinalityKeyValue("dai.agent.revision", Integer.toString(agent.revision()))
                .highCardinalityKeyValue("dai.turn.id", turnId.toString())
                .highCardinalityKeyValue("dai.model_call.id", modelCallId.toString())
                .start();
    }

    /** Ends the turn span with the outcome; failures mark it as an error so tracing backends flag it. */
    private static void finishTurnObservation(@Nullable Observation observation, TurnRecorder.Outcome outcome,
                                              TurnRecorder.Finish finish, @Nullable String errorCode,
                                              long inputTokens, long outputTokens) {
        if (observation == null) {
            return;
        }
        observation.lowCardinalityKeyValue("dai.turn.outcome", outcome.name());
        observation.lowCardinalityKeyValue("dai.turn.finish", finish.name());
        if (errorCode != null) {
            observation.lowCardinalityKeyValue("dai.turn.error_code", errorCode);
        }
        observation.highCardinalityKeyValue("dai.tokens.input", Long.toString(inputTokens));
        observation.highCardinalityKeyValue("dai.tokens.output", Long.toString(outputTokens));
        if (outcome == TurnRecorder.Outcome.FAILED) {
            observation.error(new IllegalStateException(errorCode == null ? "turn failed" : errorCode));
        }
        observation.stop();
    }

    // ─── Turn recording ───────────────────────────────────────────────────────

    /** Hands a finished turn to the recorder; whatever the recorder does never affects the turn. */
    private void recordTurn(AgentDefinition agent, AgentChatRequest request, DaiPrincipal principal, UUID turnId,
                            UUID modelCallId, @Nullable Observation turnObservation, UUID conversationId, Instant startedAt, @Nullable String traceId, boolean streaming,
                            TurnRecorder.Outcome outcome, TurnRecorder.Finish finish, @Nullable String errorCode,
                            @Nullable Integer timeToFirstTokenMs, long inputTokens, long outputTokens) {
        finishTurnObservation(turnObservation, outcome, finish, errorCode, inputTokens, outputTokens);
        try {
            turnRecorder.record(new TurnRecorder.TurnRecord(turnId, startedAt, clock.instant(), conversationId,
                    agent, principal, request.effectiveChannel(), traceId, request.clientRequestId(), outcome,
                    finish, errorCode, timeToFirstTokenMs, streaming, inputTokens, outputTokens, modelCallId));
        } catch (RuntimeException e) {
            LOG.warn("Turn recording failed for agent {} turn {}; the turn is unaffected", agent.slug(), turnId, e);
        }
    }

    /** Hands the message pair of a successful turn to the conversation recorder (never affects the turn). */
    private void recordExchange(AgentDefinition agent, AgentChatRequest request, DaiPrincipal principal, UUID turnId,
                                UUID conversationId, Instant startedAt, String userMessage, String answer) {
        if (answer.isBlank() || userMessage.isBlank()) {
            return;
        }
        try {
            // what was sent and shown: the prompt after input redaction, the answer after output redaction
            conversationRecorder.record(new ConversationRecorder.Exchange(conversationId, turnId, agent, principal,
                    request.effectiveChannel(), userMessage, answer, startedAt));
        } catch (RuntimeException e) {
            LOG.warn("Conversation recording failed for agent {} turn {}; the turn is unaffected",
                    agent.slug(), turnId, e);
        }
    }

    /** The active trace id from the logging context (set by Micrometer Tracing), if it looks like one. */
    private static @Nullable String currentTraceId() {
        String id = MDC.get("traceId");
        return id != null && id.matches("[0-9a-fA-F]{16,32}") ? id : null;
    }

    private static long[] usageOf(@Nullable ChatResponse response) {
        if (response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null) {
            return new long[] {0L, 0L};
        }
        var u = response.getMetadata().getUsage();
        return new long[] {u.getPromptTokens() != null ? u.getPromptTokens() : 0L,
                u.getCompletionTokens() != null ? u.getCompletionTokens() : 0L};
    }

    // ─── ChatClient assembly ──────────────────────────────────────────────────

    private ChatClient buildChatClient(AgentDefinition agent, DaiPrincipal principal, ResolvedModel resolved) {
        ChatModel chatModel = resolved.model();
        List<Advisor> advisors = new ArrayList<>();
        advisors.add(new InvocationGuardAdvisor(agent, principal, killSwitchChecker, budgetChecker, turnSafety));
        if (agent.memory().strategy() == MemorySpec.Strategy.SUMMARY) {
            advisors.add(new SummaryMemoryAdvisor(chatMemory, chatModel, MEMORY_ORDER));
        } else if (agent.memory().strategy() == MemorySpec.Strategy.WINDOW) {
            advisors.add(MessageChatMemoryAdvisor.builder(chatMemory)
                    .order(MEMORY_ORDER)
                    .build());
        }
        // NONE: no memory advisor
        if (knowledgeStore != null && !agent.knowledge().isEmpty()) {
            advisors.add(new KnowledgeAdvisor(knowledgeStore, agent.knowledge(), knowledgeMaxChars,
                    MEMORY_ORDER + 1));
        }
        if (agent.output().mode() == OutputSpec.Mode.JSON_SCHEMA) {
            advisors.add(new StructuredOutputValidationAdvisor(agent.output(), schemaValidator));
        }
        advisors.add(new UsageMeteringAdvisor(agent, principal, usageSink, observationRegistry));

        // With the host's registry Spring AI emits its own client/advisor spans, nested under dai.agent.turn
        ChatClient.Builder builder = ChatClient.builder(chatModel, observationRegistry, null, null)
                .defaultSystem(agent.systemPrompt())
                .defaultOptions(chatOptions(resolved.selection()))
                .defaultAdvisors(advisors);
        return builder.build();
    }

    /** The per-agent request options of the selection that resolved: model name, temperature, token limit (OQ-47). */
    static ChatOptions.Builder<?> chatOptions(ModelSelection selection) {
        ChatOptions.Builder<?> options = ChatOptions.builder().model(selection.modelName());
        if (selection.temperature() != null) {
            options.temperature(selection.temperature());
        }
        if (selection.maxTokens() != null) {
            options.maxTokens(selection.maxTokens());
        }
        return options;
    }

    // ─── Tool callbacks ───────────────────────────────────────────────────────

    /**
     * The chat-interface part of a turn's tools: every tool reports its calls as step details when the agent's UI
     * shows steps, and {@code present_choices} is added when it allows choices. Choices are streamed through the
     * turn's side channel and recorded so their answers can be validated later.
     */
    private List<ToolCallback> withChatUi(AgentDefinition agent, DaiPrincipal principal, UUID turnId, String convKey,
                                          TurnEvents sideEvents, List<ToolCallback> callbacks) {
        ChatUiSpec ui = chatUi.effective(agent);
        if (ui == null || (!ui.steps() && !ui.choices())) {
            return callbacks;
        }
        List<ToolCallback> out = new ArrayList<>(callbacks.size() + 1);
        AtomicInteger counter = new AtomicInteger();
        for (ToolCallback callback : callbacks) {
            out.add(ui.steps()
                    ? new StepReportingToolCallback(callback, sideEvents, turnSafety.redactor(), counter)
                    : callback);
        }
        if (ui.choices()) {
            String conversationKey = Sha256.of(convKey);
            out.add(new PresentChoicesTool(turnSafety.redactor(), choice -> {
                String payload = choice.toPayloadJson();
                sideEvents.emit(new StreamEvent.UiComponent(Choice.TYPE, payload, choice.componentId(), false));
                try {
                    chatUi.state().componentShown(new ChatUiState.ShownComponent(conversationKey,
                            agent.workspaceId(), agent.id(), principal.principalId(), turnId, choice.componentId(),
                            Choice.TYPE, payload));
                } catch (RuntimeException e) {
                    LOG.warn("Recording choice {} of turn {} failed; it is shown but its answer cannot be validated",
                            choice.componentId(), turnId, e);
                }
            }));
        }
        return out;
    }

    /** Adds the side-channel results of a synchronous turn: tool calls and components shown by tools. */
    private static SyncChatResult withSideEvents(SyncChatResult result, List<StreamEvent> events) {
        if (events.isEmpty()) {
            return result;
        }
        java.util.Map<String, String> toolNames = new java.util.HashMap<>();
        List<ToolCallRecord> toolCalls = new ArrayList<>(result.toolCalls());
        List<StreamEvent.UiComponent> components = new ArrayList<>(result.components());
        for (StreamEvent e : events) {
            switch (e) {
                case StreamEvent.ToolCall call -> toolNames.put(call.callId(), call.tool());
                case StreamEvent.ToolResult r -> toolCalls.add(new ToolCallRecord(r.callId(),
                        toolNames.getOrDefault(r.callId(), "unknown"), r.status()));
                case StreamEvent.UiComponent c -> components.add(c);
                default -> { }
            }
        }
        return new SyncChatResult(result.conversationId(), result.turnId(), result.message(), toolCalls,
                result.usage(), result.display(), components);
    }

    private List<ToolCallback> buildToolCallbacks(AgentDefinition agent, DaiPrincipal principal,
                                                    Authentication authentication, AgentChatRequest request,
                                                    UUID turnId, UUID modelCallId,
                                                    Observation turnObservation) {
        if (toolBridge == null || agent.tools().isEmpty()) {
            return List.of();
        }
        return toolBridge.buildCallbacks(agent, principal, authentication, metadataRegistry.current(),
                ToolCallScope.ofTurn(request.effectiveChannel(), turnId, modelCallId, turnObservation));
    }

    // ─── Conversation key ─────────────────────────────────────────────────────

    private static String convKey(DaiPrincipal principal, AgentDefinition agent, UUID conversationId) {
        return ConversationKeys.memoryKey(agent.workspaceId(), agent.id(), principal.principalId(), conversationId);
    }

    // ─── Response mapping ─────────────────────────────────────────────────────

    private static SyncChatResult mapSyncResult(ChatResponse response,
                                                  UUID conversationId, UUID turnId) {
        String text = "";
        var result = response.getResult();
        if (result != null && result.getOutput().getText() != null) {
            text = result.getOutput().getText();
        }
        long promptTokens = 0L;
        long completionTokens = 0L;
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            var u = response.getMetadata().getUsage();
            if (u.getPromptTokens() != null) promptTokens = u.getPromptTokens();
            if (u.getCompletionTokens() != null) completionTokens = u.getCompletionTokens();
        }
        return new SyncChatResult(conversationId, turnId, text,
                List.of(), new UsageRecord(promptTokens, completionTokens));
    }

    private static String extractText(ChatResponse response) {
        if (response.getResult() == null) return "";
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    /** Output guardrails of a synchronous answer: redaction, length limit and structured display (LLD-06 §8). */
    private SyncChatResult guardSyncResult(AgentDefinition agent, SyncChatResult raw) {
        TurnSafety.OutputGuard guard = turnSafety.outputGuard(agent);
        if (!guard.active()) {
            return raw;
        }
        return new SyncChatResult(raw.conversationId(), raw.turnId(), guard.finish(raw.message()), raw.toolCalls(),
                raw.usage(), guard.display(raw.message()));
    }

    private static void appendRecorded(StringBuilder answer, String text) {
        synchronized (answer) {
            int room = MAX_RECORDED_ANSWER_CHARS - answer.length();
            if (room > 0) {
                answer.append(text, 0, Math.min(text.length(), room));
            }
        }
    }

    private static void addDisplay(List<StreamEvent> events, @Nullable StructuredResponse display) {
        if (display != null) {
            events.add(new StreamEvent.UiComponent(STRUCTURED_RESPONSE, display.toJson()));
        }
    }

    /** Streamed turns bypass the call advisor chain, so their usage is recorded here (failures never break the turn). */
    private void recordStreamUsage(AgentDefinition agent, DaiPrincipal principal, @Nullable ChatResponse last) {
        if (last == null || last.getMetadata() == null || last.getMetadata().getUsage() == null) {
            return;
        }
        var u = last.getMetadata().getUsage();
        long prompt = u.getPromptTokens() != null ? u.getPromptTokens() : 0L;
        long completion = u.getCompletionTokens() != null ? u.getCompletionTokens() : 0L;
        if (prompt + completion == 0) {
            return;
        }
        try {
            usageSink.record(agent, principal, prompt, completion);
        } catch (RuntimeException e) {
            LOG.warn("Usage recording failed for streamed turn of agent {} principal {}; usage not recorded",
                    agent.slug(), principal.principalId(), e);
        }
    }

    private static List<StreamEvent> buildEndingEvents(@Nullable ChatResponse last,
                                                        AgentDefinition agent, UUID turnId) {
        List<StreamEvent> events = new ArrayList<>(2);
        if (last != null && last.getMetadata() != null && last.getMetadata().getUsage() != null) {
            var u = last.getMetadata().getUsage();
            long prompt = u.getPromptTokens() != null ? u.getPromptTokens() : 0L;
            long completion = u.getCompletionTokens() != null ? u.getCompletionTokens() : 0L;
            events.add(new StreamEvent.UsageEvent(prompt, completion, 0L, agent.model().modelName()));
        }
        events.add(new StreamEvent.TurnEnd("stop", null));
        return events;
    }

    // ─── Error helpers ────────────────────────────────────────────────────────

    private static String errorCode(Throwable e) {
        if (e instanceof AgentInvocationException aie) return aie.code();
        if (e instanceof java.util.concurrent.TimeoutException) return "turn-timeout";
        return "stream-error";
    }

    private static boolean isRetryable(Throwable e) {
        if (e instanceof AgentInvocationException aie) return aie.retryable();
        return e instanceof java.util.concurrent.TimeoutException;
    }
}
