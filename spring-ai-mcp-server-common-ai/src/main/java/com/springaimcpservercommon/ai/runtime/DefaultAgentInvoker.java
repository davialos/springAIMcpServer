package com.springaimcpservercommon.ai.runtime;

import com.springaimcpservercommon.ai.advisor.InvocationGuardAdvisor;
import com.springaimcpservercommon.ai.advisor.JsonSchemaValidationPort;
import com.springaimcpservercommon.ai.advisor.StructuredOutputValidationAdvisor;
import com.springaimcpservercommon.ai.advisor.SummaryMemoryAdvisor;
import com.springaimcpservercommon.ai.advisor.UsageMeteringAdvisor;
import com.springaimcpservercommon.ai.agent.AgentDefinition;
import com.springaimcpservercommon.ai.agent.MemorySpec;
import com.springaimcpservercommon.ai.agent.OutputSpec;
import com.springaimcpservercommon.ai.model.ModelRouter;
import com.springaimcpservercommon.ai.tool.ToolBridge;
import com.springaimcpservercommon.core.catalog.MetadataRegistry;
import com.springaimcpservercommon.core.id.Ids;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>Not a Spring {@code @Component} — registered by {@code DaiAiAutoConfiguration} when a
 * {@link ModelRouter} and {@link MetadataRegistry} bean are present.
 */
@NullMarked
public final class DefaultAgentInvoker implements AgentInvoker {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultAgentInvoker.class);
    private static final int MEMORY_ORDER = Ordered.HIGHEST_PRECEDENCE + 201;

    private final ModelRouter modelRouter;
    private final @Nullable ToolBridge toolBridge;
    private final MetadataRegistry metadataRegistry;
    private final InvocationGuardAdvisor.KillSwitchChecker killSwitchChecker;
    private final InvocationGuardAdvisor.BudgetChecker budgetChecker;
    private final UsageMeteringAdvisor.UsageSink usageSink;
    private final ObservationRegistry observationRegistry;
    private final ChatMemory chatMemory;
    private final @Nullable JsonSchemaValidationPort schemaValidator;

    /**
     * Creates the invoker.
     *
     * @param modelRouter          resolves the {@link ChatModel} for each turn
     * @param toolBridge           assembles per-request tool callbacks; {@code null} if tools are unavailable
     * @param metadataRegistry     current effective catalog snapshot
     * @param killSwitchChecker    runtime kill-switch check
     * @param budgetChecker        token-budget pre-check
     * @param usageSink            token usage accounting
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
                                ObservationRegistry observationRegistry,
                                ChatMemory chatMemory,
                                @Nullable JsonSchemaValidationPort schemaValidator) {
        this.modelRouter = Objects.requireNonNull(modelRouter, "modelRouter");
        this.toolBridge = toolBridge;
        this.metadataRegistry = Objects.requireNonNull(metadataRegistry, "metadataRegistry");
        this.killSwitchChecker = Objects.requireNonNull(killSwitchChecker, "killSwitchChecker");
        this.budgetChecker = Objects.requireNonNull(budgetChecker, "budgetChecker");
        this.usageSink = Objects.requireNonNull(usageSink, "usageSink");
        this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry");
        this.chatMemory = Objects.requireNonNull(chatMemory, "chatMemory");
        this.schemaValidator = schemaValidator;
    }

    // ─── Sync turn ────────────────────────────────────────────────────────────

    @Override
    public SyncChatResult invoke(AgentDefinition agent, AgentChatRequest request,
                                  DaiPrincipal principal, Authentication authentication) {
        UUID conversationId = request.conversationId() != null ? request.conversationId() : Ids.newId();
        UUID turnId = Ids.newId();
        LOG.debug("Agent {} sync turn {} for principal {}", agent.slug(), turnId, principal.principalId());

        try {
            if (!budgetChecker.hasRemainingBudget(agent, principal)) {
                LOG.warn("Agent {} budget exhausted for principal {}; sync turn {} rejected",
                        agent.slug(), principal.principalId(), turnId);
                throw new AgentInvocationException("budget-exhausted",
                        "The usage budget for this agent is exhausted for the current period.", false);
            }
            ChatModel chatModel = modelRouter.resolve(agent.model(), principal);
            List<ToolCallback> callbacks = buildToolCallbacks(agent, principal, authentication);
            ChatClient client = buildChatClient(agent, principal, chatModel);
            String convKey = convKey(principal, agent, conversationId);

            ChatResponse response = client.prompt()
                    .user(request.message())
                    .tools(callbacks.toArray(new ToolCallback[0]))
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, convKey))
                    .call()
                    .chatResponse();

            if (response == null) {
                throw new AgentInvocationException("empty-response",
                        "Agent returned no response.", true);
            }
            return mapSyncResult(response, conversationId, turnId);
        } catch (AgentInvocationException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Agent {} sync turn {} failed for principal {}",
                    agent.slug(), turnId, principal.principalId(), e);
            throw new AgentInvocationException("execution-error", "Agent invocation failed.", true);
        }
    }

    // ─── Streaming turn ───────────────────────────────────────────────────────

    @Override
    public Flux<StreamEvent> stream(AgentDefinition agent, AgentChatRequest request,
                                     DaiPrincipal principal, Authentication authentication) {
        UUID conversationId = request.conversationId() != null ? request.conversationId() : Ids.newId();
        UUID turnId = Ids.newId();

        return Flux.defer(() -> {
            LOG.debug("Agent {} stream turn {} for principal {}",
                    agent.slug(), turnId, principal.principalId());
            try {
                if (!budgetChecker.hasRemainingBudget(agent, principal)) {
                    LOG.warn("Agent {} budget exhausted for principal {}; stream turn {} rejected",
                            agent.slug(), principal.principalId(), turnId);
                    return Flux.just(new StreamEvent.ErrorEvent(
                            "/errors/agent/budget-exhausted", "Usage limit reached",
                            "budget-exhausted", false, turnId));
                }
                ChatModel chatModel = modelRouter.resolve(agent.model(), principal);
                List<ToolCallback> callbacks = buildToolCallbacks(agent, principal, authentication);
                ChatClient client = buildChatClient(agent, principal, chatModel);
                String convKey = convKey(principal, agent, conversationId);

                AtomicInteger seq = new AtomicInteger(0);
                AtomicReference<@Nullable ChatResponse> lastResponse = new AtomicReference<>();

                Flux<StreamEvent> content = client.prompt()
                        .user(request.message())
                        .tools(callbacks.toArray(new ToolCallback[0]))
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, convKey))
                        .stream()
                        .chatResponse()
                        .timeout(agent.limits().turnTimeout())
                        .doOnNext(lastResponse::set)
                        .flatMapIterable(r -> extractTextDeltas(r, seq));

                Flux<StreamEvent> ending = Flux.defer(() -> {
                    ChatResponse last = lastResponse.get();
                    recordStreamUsage(agent, principal, last);
                    return Flux.fromIterable(buildEndingEvents(last, agent, turnId));
                });

                StreamEvent turnStart = new StreamEvent.TurnStart(
                        turnId, conversationId, agent.slug(), agent.revision(),
                        StreamEvent.TurnStart.PROTOCOL);

                return Flux.concat(
                        Flux.just(turnStart),
                        content.concatWith(ending)
                ).onErrorResume(e -> {
                    LOG.error("Agent {} stream error for principal {}",
                            agent.slug(), principal.principalId(), e);
                    String code = errorCode(e);
                    return Flux.just(new StreamEvent.ErrorEvent(
                            "/errors/agent/" + code, "Agent stream error",
                            code, isRetryable(e), turnId));
                });
            } catch (Exception e) {
                LOG.error("Agent {} stream setup failed for principal {}",
                        agent.slug(), principal.principalId(), e);
                return Flux.just(new StreamEvent.ErrorEvent(
                        "/errors/agent/stream-error", "Stream setup failed",
                        "stream-error", true, turnId));
            }
        });
    }

    // ─── ChatClient assembly ──────────────────────────────────────────────────

    private ChatClient buildChatClient(AgentDefinition agent, DaiPrincipal principal, ChatModel chatModel) {
        List<Advisor> advisors = new ArrayList<>();
        advisors.add(new InvocationGuardAdvisor(agent, principal, killSwitchChecker, budgetChecker));
        if (agent.memory().strategy() == MemorySpec.Strategy.SUMMARY) {
            advisors.add(new SummaryMemoryAdvisor(chatMemory, chatModel, MEMORY_ORDER));
        } else if (agent.memory().strategy() == MemorySpec.Strategy.WINDOW) {
            advisors.add(MessageChatMemoryAdvisor.builder(chatMemory)
                    .order(MEMORY_ORDER)
                    .build());
        }
        // NONE: no memory advisor
        if (agent.output().mode() == OutputSpec.Mode.JSON_SCHEMA) {
            advisors.add(new StructuredOutputValidationAdvisor(agent.output(), schemaValidator));
        }
        advisors.add(new UsageMeteringAdvisor(agent, principal, usageSink, observationRegistry));

        ChatClient.Builder builder = ChatClient.builder(chatModel)
                .defaultSystem(agent.systemPrompt())
                .defaultAdvisors(advisors);
        return builder.build();
    }

    // ─── Tool callbacks ───────────────────────────────────────────────────────

    private List<ToolCallback> buildToolCallbacks(AgentDefinition agent, DaiPrincipal principal,
                                                    Authentication authentication) {
        if (toolBridge == null || agent.tools().isEmpty()) {
            return List.of();
        }
        return toolBridge.buildCallbacks(agent, principal, authentication, metadataRegistry.current());
    }

    // ─── Conversation key ─────────────────────────────────────────────────────

    private static String convKey(DaiPrincipal principal, AgentDefinition agent, UUID conversationId) {
        return agent.workspaceId() + ":" + agent.id() + ":" + principal.principalId() + ":" + conversationId;
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
            if (u.getGenerationTokens() != null) completionTokens = u.getGenerationTokens();
        }
        return new SyncChatResult(conversationId, turnId, text,
                List.of(), new UsageRecord(promptTokens, completionTokens));
    }

    private static List<StreamEvent> extractTextDeltas(ChatResponse response, AtomicInteger seq) {
        if (response.getResult() == null) return List.of();
        String text = response.getResult().getOutput().getText();
        if (text == null || text.isEmpty()) return List.of();
        return List.of(new StreamEvent.TextDelta(seq.incrementAndGet(), text));
    }

    /** Streamed turns bypass the call advisor chain, so their usage is recorded here (failures never break the turn). */
    private void recordStreamUsage(AgentDefinition agent, DaiPrincipal principal, @Nullable ChatResponse last) {
        if (last == null || last.getMetadata() == null || last.getMetadata().getUsage() == null) {
            return;
        }
        var u = last.getMetadata().getUsage();
        long prompt = u.getPromptTokens() != null ? u.getPromptTokens() : 0L;
        long completion = u.getGenerationTokens() != null ? u.getGenerationTokens() : 0L;
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
            long completion = u.getGenerationTokens() != null ? u.getGenerationTokens() : 0L;
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
