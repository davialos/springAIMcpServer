package com.springaimcpservercommon.ai.advisor;

import com.springaimcpservercommon.ai.agent.AgentDefinition;
import com.springaimcpservercommon.ai.agent.GuardrailSpec;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.Ordered;

import java.util.List;
import java.util.Objects;
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
 *   <li>Budget pre-check: delegates to the {@link BudgetChecker} port.</li>
 * </ol>
 *
 * <p>On rejection, returns a safe synthetic {@link ChatResponse} (no model call) so the host
 * receives a well-formed response (LLD-12 §4: fail the feature, not the host).
 */
@NullMarked
public final class InvocationGuardAdvisor implements CallAroundAdvisor {

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
     * <p>Implemented in the {@code webmvc} or {@code autoconfigure} module; injected at construction time.
     */
    @FunctionalInterface
    public interface BudgetChecker {
        /**
         * @param principal the calling principal
         * @param agentId   the agent being invoked
         * @return {@code true} if the budget allows the turn to proceed
         */
        boolean hasRemainingBudget(DaiPrincipal principal, java.util.UUID agentId);
    }

    private final AgentDefinition agent;
    private final DaiPrincipal principal;
    private final KillSwitchChecker killSwitchChecker;
    private final BudgetChecker budgetChecker;

    /**
     * Creates the advisor for a specific agent turn.
     *
     * @param agent             the agent definition governing this turn
     * @param principal         the calling principal
     * @param killSwitchChecker checks whether the agent is currently enabled
     * @param budgetChecker     budget pre-check port
     */
    public InvocationGuardAdvisor(AgentDefinition agent, DaiPrincipal principal,
                                   KillSwitchChecker killSwitchChecker, BudgetChecker budgetChecker) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.killSwitchChecker = Objects.requireNonNull(killSwitchChecker, "killSwitchChecker");
        this.budgetChecker = Objects.requireNonNull(budgetChecker, "budgetChecker");
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        String userInput = extractUserInput(request);

        // Kill switch: agent disabled at runtime
        if (!killSwitchChecker.isEnabled(agent.id())) {
            LOG.warn("Agent {} is kill-switched; blocking turn for principal {}",
                    agent.slug(), principal.principalId());
            return blocked(request, "agent_disabled",
                    "This agent is currently unavailable. Please try again later.");
        }

        // Input size guardrail
        GuardrailSpec g = agent.guardrails();
        if (g.maxInputChars() > 0 && userInput.length() > g.maxInputChars()) {
            LOG.info("Agent {} input too large ({} > {}) for principal {}",
                    agent.slug(), userInput.length(), g.maxInputChars(), principal.principalId());
            return blocked(request, "input_too_large",
                    "Your message is too long. Please shorten it and try again.");
        }

        // Blocked patterns
        for (String pattern : g.blockedPatterns()) {
            if (Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                    .matcher(userInput).find()) {
                LOG.info("Agent {} input matched blocked pattern for principal {}",
                        agent.slug(), principal.principalId());
                return blocked(request, "input_blocked",
                        "Your message contains content that cannot be processed.");
            }
        }

        // Topic allowlist
        if (!g.topicAllowList().isEmpty()) {
            boolean matched = g.topicAllowList().stream()
                    .anyMatch(topic -> userInput.toLowerCase(java.util.Locale.ROOT)
                            .contains(topic.toLowerCase(java.util.Locale.ROOT)));
            if (!matched) {
                LOG.info("Agent {} input does not match topic allowlist for principal {}",
                        agent.slug(), principal.principalId());
                return blocked(request, "off_topic",
                        "I can only help with: " + String.join(", ", g.topicAllowList()) + ".");
            }
        }

        // Budget check
        if (!budgetChecker.hasRemainingBudget(principal, agent.id())) {
            LOG.warn("Agent {} budget exhausted for principal {}", agent.slug(), principal.principalId());
            return blocked(request, "budget_exhausted",
                    "Usage limit reached. Please contact your administrator.");
        }

        return chain.nextAroundCall(request);
    }

    private static String extractUserInput(AdvisedRequest request) {
        String user = request.userText();
        return user != null ? user : "";
    }

    private static AdvisedResponse blocked(AdvisedRequest request, String code, String message) {
        AssistantMessage msg = new AssistantMessage("[" + code + "] " + message);
        ChatResponse response = new ChatResponse(List.of(new Generation(msg)));
        return new AdvisedResponse(response, request.adviseContext());
    }
}
