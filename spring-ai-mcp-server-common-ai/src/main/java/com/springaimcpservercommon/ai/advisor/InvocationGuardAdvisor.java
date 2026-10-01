package com.springaimcpservercommon.ai.advisor;

import com.springaimcpservercommon.ai.agent.AgentDefinition;
import com.springaimcpservercommon.ai.agent.GuardrailSpec;
import com.springaimcpservercommon.ai.safety.TurnSafety;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Pre-turn advisor: enforces budget caps, kill switch, and input guardrails (LLD-06 §4).
 *
 * <p>Order: {@link Ordered#HIGHEST_PRECEDENCE} + 200 — runs before {@code ToolCallingAdvisor}
 * ({@code HIGHEST_PRECEDENCE + 300}) so a budget/kill-switch violation aborts the turn
 * before any tool is even invoked.
 *
 * <p>Checks (in order):
 * <ol>
 *   <li>Agent-level kill switch (fail-closed if the agent is disabled).</li>
 *   <li>Input size: rejects prompts exceeding {@link GuardrailSpec#maxInputChars()}.</li>
 *   <li>Input blocked patterns: rejects if the user input matches any {@link GuardrailSpec#blockedPatterns()}.</li>
 *   <li>Topic allowlist: when non-empty, rejects if the input does not mention any allowed topic.</li>
 *   <li>Prompt validation ({@link TurnSafety#validatePrompt}): malicious content and business scope against the
 *       effective catalog, plus host {@code PromptValidator}s (F-76).</li>
 *   <li>Budget pre-check: delegates to the {@link BudgetChecker} port.</li>
 * </ol>
 *
 * <p>On rejection, returns a safe synthetic {@link ChatResponse} (no model call) so the host
 * receives a well-formed response (LLD-12 §4: fail the feature, not the host).
 */
@NullMarked
public final class InvocationGuardAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger LOG = LoggerFactory.getLogger(InvocationGuardAdvisor.class);
    private static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 200;

    /**
     * Port: checks whether the agent is currently active (not kill-switched).
     *
     * <p>A kill switch can be toggled at runtime from the admin control plane (LLD-12 §3).
     * The default implementation in {@code autoconfigure} reads from the effective catalog snapshot.
     */
    @FunctionalInterface
    public interface KillSwitchChecker {
        /**
         * @param agentId the agent being invoked
         * @return {@code true} if the agent is enabled (not kill-switched)
         */
        boolean isEnabled(java.util.UUID agentId);
    }

    /**
     * Port: checks whether the principal has remaining token/cost budget.
     *
     * <p>Implemented in the {@code autoconfigure} module on top of the usage ledger; injected at
     * construction time. Implementations must be cheap (called on every turn) and must decide what to do
     * when their backing store is unavailable; the default implementation fails open.
     */
    @FunctionalInterface
    public interface BudgetChecker {
        /**
         * @param agent     the agent being invoked (its workspace and id scope the applicable budgets)
         * @param principal the calling principal
         * @return {@code true} if the budget allows the turn to proceed, {@code false} if a hard limit
         *         that applies to this turn has been reached
         */
        boolean hasRemainingBudget(AgentDefinition agent, DaiPrincipal principal);
    }

    private final AgentDefinition agent;
    private final DaiPrincipal principal;
    private final KillSwitchChecker killSwitchChecker;
    private final BudgetChecker budgetChecker;
    private final TurnSafety turnSafety;

    /**
     * Advisor-context key under which the invoker passes the prompt as typed, so that validation judges the user's
     * words even when the message sent to the model has had personal data redacted.
     */
    public static final String RAW_INPUT_KEY = "dai.rawUserInput";

    /**
     * Advisor-context key the streaming path sets when it has already run {@link #check} itself (it must answer with
     * an error event before the stream opens), so validators are not run a second time for the same prompt.
     */
    public static final String PRECHECKED_KEY = "dai.inputPrechecked";

    /**
     * Creates the advisor for a specific agent turn, without prompt validation.
     *
     * @param agent             the agent definition governing this turn
     * @param principal         the calling principal
     * @param killSwitchChecker checks whether the agent is currently enabled
     * @param budgetChecker     budget pre-check port
     */
    public InvocationGuardAdvisor(AgentDefinition agent, DaiPrincipal principal,
                                   KillSwitchChecker killSwitchChecker, BudgetChecker budgetChecker) {
        this(agent, principal, killSwitchChecker, budgetChecker, TurnSafety.disabled());
    }

    /**
     * Creates the advisor for a specific agent turn.
     *
     * @param agent             the agent definition governing this turn
     * @param principal         the calling principal
     * @param killSwitchChecker checks whether the agent is currently enabled
     * @param budgetChecker     budget pre-check port
     * @param turnSafety        prompt validation (F-76)
     */
    public InvocationGuardAdvisor(AgentDefinition agent, DaiPrincipal principal,
                                   KillSwitchChecker killSwitchChecker, BudgetChecker budgetChecker,
                                   TurnSafety turnSafety) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.killSwitchChecker = Objects.requireNonNull(killSwitchChecker, "killSwitchChecker");
        this.budgetChecker = Objects.requireNonNull(budgetChecker, "budgetChecker");
        this.turnSafety = Objects.requireNonNull(turnSafety, "turnSafety");
    }

    @Override
    public String getName() {
        return "daiInvocationGuard";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    /**
     * A rejected turn: a stable machine code and a message that is safe to show the caller.
     *
     * @param code    stable code, e.g. {@code agent_disabled}, {@code input_too_large}, {@code budget_exhausted}
     * @param message caller-safe explanation
     */
    public record Violation(String code, String message) {}

    /**
     * Runs the pre-turn checks that depend only on the agent, the caller and the user's input: kill switch, input
     * size, blocked patterns, topic allow-list and prompt validation. Shared by the call advisor and the streaming path, which does not
     * pass through call advisors (LLD-06 §4).
     *
     * @param userInput the user's message
     * @return the violation, or empty when the turn may proceed
     */
    public Optional<Violation> checkInput(String userInput) {
        if (!killSwitchChecker.isEnabled(agent.id())) {
            LOG.warn("Agent {} is kill-switched; blocking turn for principal {}",
                    agent.slug(), principal.principalId());
            return Optional.of(new Violation("agent_disabled",
                    "This agent is currently unavailable. Please try again later."));
        }
        GuardrailSpec g = agent.guardrails();
        if (g.maxInputChars() > 0 && userInput.length() > g.maxInputChars()) {
            LOG.info("Agent {} input too large ({} > {}) for principal {}",
                    agent.slug(), userInput.length(), g.maxInputChars(), principal.principalId());
            return Optional.of(new Violation("input_too_large",
                    "Your message is too long. Please shorten it and try again."));
        }
        for (String pattern : g.blockedPatterns()) {
            if (Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(userInput).find()) {
                LOG.info("Agent {} input matched blocked pattern for principal {}",
                        agent.slug(), principal.principalId());
                return Optional.of(new Violation("input_blocked",
                        "Your message contains content that cannot be processed."));
            }
        }
        if (!g.topicAllowList().isEmpty()) {
            String lower = userInput.toLowerCase(java.util.Locale.ROOT);
            boolean matched = g.topicAllowList().stream()
                    .anyMatch(topic -> lower.contains(topic.toLowerCase(java.util.Locale.ROOT)));
            if (!matched) {
                LOG.info("Agent {} input does not match topic allowlist for principal {}",
                        agent.slug(), principal.principalId());
                return Optional.of(new Violation("off_topic",
                        "I can only help with: " + String.join(", ", g.topicAllowList()) + "."));
            }
        }
        TurnSafety.Rejection rejection = turnSafety.validatePrompt(agent, principal, userInput);
        if (rejection != null) {
            return Optional.of(new Violation(rejection.code(), rejection.message()));
        }
        return Optional.empty();
    }

    /**
     * Runs all pre-turn checks: {@link #checkInput(String)} then the budget.
     *
     * @param userInput the user's message
     * @return the violation, or empty when the turn may proceed
     */
    public Optional<Violation> check(String userInput) {
        Optional<Violation> input = checkInput(userInput);
        if (input.isPresent()) {
            return input;
        }
        if (!budgetChecker.hasRemainingBudget(agent, principal)) {
            LOG.warn("Agent {} budget exhausted for principal {}", agent.slug(), principal.principalId());
            return Optional.of(new Violation("budget_exhausted",
                    "Usage limit reached. Please contact your administrator."));
        }
        return Optional.empty();
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (Boolean.TRUE.equals(request.context().get(PRECHECKED_KEY))) {
            return chain.nextCall(request);
        }
        Optional<Violation> violation = check(extractUserInput(request));
        if (violation.isPresent()) {
            return blocked(request, violation.get().code(), violation.get().message());
        }
        return chain.nextCall(request);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        if (Boolean.TRUE.equals(request.context().get(PRECHECKED_KEY))) {
            return chain.nextStream(request);
        }
        Optional<Violation> violation = check(extractUserInput(request));
        if (violation.isPresent()) {
            return Flux.just(blocked(request, violation.get().code(), violation.get().message()));
        }
        return chain.nextStream(request);
    }

    private static String extractUserInput(ChatClientRequest request) {
        if (request.context().get(RAW_INPUT_KEY) instanceof String raw) {
            return raw;
        }
        String user = request.prompt().getUserMessage().getText();
        return user != null ? user : "";
    }

    private static ChatClientResponse blocked(ChatClientRequest request, String code, String message) {
        AssistantMessage msg = new AssistantMessage("[" + code + "] " + message);
        ChatResponse response = new ChatResponse(List.of(new Generation(msg)));
        return new ChatClientResponse(response, request.context());
    }
}
