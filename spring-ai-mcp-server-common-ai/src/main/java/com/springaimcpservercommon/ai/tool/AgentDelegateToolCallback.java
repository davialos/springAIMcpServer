package com.springaimcpservercommon.ai.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springaimcpservercommon.ai.agent.AgentDefinition;
import com.springaimcpservercommon.ai.runtime.AgentInvoker;
import com.springaimcpservercommon.ai.runtime.AgentInvoker.AgentChatRequest;
import com.springaimcpservercommon.ai.runtime.AgentInvoker.AgentInvocationException;
import com.springaimcpservercommon.ai.runtime.AgentInvoker.SyncChatResult;
import com.springaimcpservercommon.core.id.Ids;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.context.ToolContext;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link ToolCallback} that delegates a tool call to a nested agent (LLD-07 §5.2).
 *
 * <p>The tool exposes a single {@code message} parameter. When invoked, it calls the target
 * agent synchronously via {@link AgentInvoker#invoke}, runs as the same caller (ADR-0008),
 * and returns the sub-agent's text response wrapped in a {@link ToolResultEnvelope}.
 *
 * <p>Sub-agent failures ({@link AgentInvocationException}) are caught and returned as
 * {@link ToolResultStatus#ERROR} envelopes so they do not break the parent turn (LLD-12 §4).
 *
 * <p>Package-private — constructed exclusively through {@link ToolBridge.AgentCallbackFactory}.
 */
@NullMarked
final class AgentDelegateToolCallback implements ToolCallback {

    private static final Logger LOG = LoggerFactory.getLogger(AgentDelegateToolCallback.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MESSAGE_PARAM = "message";
    private static final String INPUT_SCHEMA =
            "{\"type\":\"object\"," +
            "\"properties\":{\"message\":{\"type\":\"string\"," +
            "\"description\":\"The message to send to this agent\"}}," +
            "\"required\":[\"message\"]}";

    private final AgentInvoker agentInvoker;
    private final AgentDefinition targetAgent;
    private final DaiPrincipal principal;
    private final Authentication authentication;
    private final ToolDefinition toolDefinition;

    /**
     * Creates the callback.
     *
     * @param agentInvoker   the invoker used to run the sub-agent turn
     * @param targetAgent    the agent to delegate to
     * @param principal      the calling principal (sub-agent runs as the same caller, ADR-0008)
     * @param authentication Spring Security authentication for tool permission checks inside the sub-agent
     * @param binding        the tool binding (provides the stable name and optional description override)
     */
    AgentDelegateToolCallback(AgentInvoker agentInvoker,
                               AgentDefinition targetAgent,
                               DaiPrincipal principal,
                               Authentication authentication,
                               ToolBinding binding) {
        this.agentInvoker = Objects.requireNonNull(agentInvoker, "agentInvoker");
        this.targetAgent = Objects.requireNonNull(targetAgent, "targetAgent");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        Objects.requireNonNull(binding, "binding");
        String description = binding.descriptionOverride() != null
                ? binding.descriptionOverride()
                : "Ask the \"" + targetAgent.displayName() + "\" agent a question or request a task.";
        this.toolDefinition = ToolDefinition.builder()
                .name(binding.toolName())
                .description(description)
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return ToolMetadata.builder().returnDirect(false).build();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        String message = extractMessage(toolInput);
        String requestId = Ids.newId().toString();

        LOG.debug("Sub-agent tool {} delegating to agent {} for principal {}",
                toolDefinition.name(), targetAgent.slug(), principal.principalId());

        try {
            SyncChatResult result = agentInvoker.invoke(
                    targetAgent,
                    new AgentChatRequest(null, message, requestId),
                    principal,
                    authentication);
            // Wrap the sub-agent's text response as the single data item
            return ToolResultEnvelope.ok(
                    toolDefinition.name(),
                    null,
                    List.of(result.message()),
                    Map.of("agent", targetAgent.slug()),
                    false,
                    false,
                    null).toJson();
        } catch (AgentInvocationException e) {
            LOG.info("Sub-agent {} invocation failed ({}): {}",
                    targetAgent.slug(), e.code(), e.getMessage());
            return ToolResultEnvelope.error(toolDefinition.name(), e.code(), e.getMessage()).toJson();
        } catch (Exception e) {
            LOG.warn("Sub-agent {} unexpected error for principal {}",
                    targetAgent.slug(), principal.principalId(), e);
            return ToolResultEnvelope.error(toolDefinition.name(), "sub_agent_error",
                    "Sub-agent invocation failed unexpectedly.").toJson();
        }
    }

    // ── private helper ────────────────────────────────────────────────────────

    /**
     * Extracts the {@code "message"} field from the tool input JSON.
     * Falls back to passing the raw input string so the sub-agent still receives something
     * even when the parent model sends a structurally unexpected call.
     */
    private static String extractMessage(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) return "";
        try {
            JsonNode root = MAPPER.readTree(toolInput);
            JsonNode node = root.path(MESSAGE_PARAM);
            if (!node.isMissingNode() && node.isTextual()) {
                return node.asText();
            }
        } catch (JsonProcessingException e) {
            LOG.debug("Sub-agent tool input is not JSON; forwarding raw input");
        }
        return toolInput;
    }
}
