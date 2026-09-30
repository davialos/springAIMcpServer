package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.autoconfigure.TraceAdminController.TraceNodeDto;
import com.springaimcpservercommon.autoconfigure.TraceAdminController.TraceSummaryDto;
import com.springaimcpservercommon.autoconfigure.TraceAdminController.TraceTreeDto;
import com.springaimcpservercommon.persistence.telemetry.AgentTurn;
import com.springaimcpservercommon.persistence.telemetry.McpRequest;
import com.springaimcpservercommon.persistence.telemetry.ModelCall;
import com.springaimcpservercommon.persistence.telemetry.ToolInvocation;
import com.springaimcpservercommon.persistence.telemetry.ToolInvocationStatus;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns the flat telemetry rows of one turn or MCP request into the call tree the trace viewer shows (F-72):
 * the request or turn at the root, model calls under a turn, and every tool call under the model call it refers to, or
 * directly under the root when it refers to none or to a call without a row. The runtime records one model call per
 * turn that covers the whole tool loop and its tool calls refer to it; a turn that failed before any tokens were
 * reported has no model call row, so its tool calls sit under the turn (OQ-43). Children are ordered by start time.
 *
 * <p>Pure and store-free. Nodes carry timings relative to the root ({@code offsetMs}) and only what the telemetry
 * tables hold: hashes, counts and statuses, plus the redacted arguments when they were stored — never results or
 * conversation content.
 */
@NullMarked
final class TraceTreeBuilder {

    static final String TURN = "TURN";
    static final String MODEL_CALL = "MODEL_CALL";
    static final String TOOL_CALL = "TOOL_CALL";
    static final String MCP_REQUEST = "MCP_REQUEST";

    private static final Comparator<TraceNodeDto> BY_START =
            Comparator.comparing(TraceNodeDto::startedAt).thenComparing(TraceNodeDto::id);

    private TraceTreeBuilder() {
    }

    /** Tree of an agent turn with its model calls and tool invocations. */
    static TraceTreeDto turn(AgentTurn turn, List<ModelCall> modelCalls, List<ToolInvocation> tools) {
        Instant origin = turn.getStartedAt();
        Map<UUID, List<TraceNodeDto>> toolsByCall = new LinkedHashMap<>();
        List<TraceNodeDto> rootTools = new java.util.ArrayList<>();
        Set<UUID> callIds = new HashSet<>();
        modelCalls.forEach(c -> callIds.add(c.getId()));
        for (ToolInvocation tool : tools) {
            TraceNodeDto node = toolNode(tool, origin);
            UUID caller = tool.getModelCallId();
            if (caller != null && callIds.contains(caller)) {
                toolsByCall.computeIfAbsent(caller, k -> new java.util.ArrayList<>()).add(node);
            } else {
                rootTools.add(node);
            }
        }
        List<TraceNodeDto> children = new java.util.ArrayList<>(rootTools);
        for (ModelCall call : modelCalls) {
            List<TraceNodeDto> under = toolsByCall.getOrDefault(call.getId(), List.of()).stream().sorted(BY_START)
                    .toList();
            children.add(modelNode(call, origin, under));
        }
        children.sort(BY_START);
        Map<String, Object> attributes = new LinkedHashMap<>();
        put(attributes, "channel", turn.getChannel());
        put(attributes, "principalId", turn.getPrincipalId());
        put(attributes, "agentResourceId", turn.getAgentResourceId());
        put(attributes, "agentRevisionId", turn.getAgentRevisionId());
        put(attributes, "conversationId", turn.getConversationId());
        put(attributes, "traceId", turn.getTraceId());
        put(attributes, "finishReason", turn.getFinishReason());
        put(attributes, "timeToFirstTokenMs", turn.getTimeToFirstTokenMs());
        TraceNodeDto root = new TraceNodeDto(TURN, turn.getId(), "agent turn", turn.getStartedAt(), turn.getEndedAt(),
                0, millis(turn.getStartedAt(), turn.getEndedAt()), turn.getOutcome().name(), turn.getErrorCode(),
                attributes, List.copyOf(children));
        return new TraceTreeDto(root, summary(root.durationMs(), modelCalls, tools));
    }

    /** Tree of an MCP request with the tool calls it made. */
    static TraceTreeDto mcpRequest(McpRequest request, List<ToolInvocation> tools) {
        Instant origin = request.getReceivedAt();
        List<TraceNodeDto> children = tools.stream().map(t -> toolNode(t, origin)).sorted(BY_START).toList();
        Map<String, Object> attributes = new LinkedHashMap<>();
        put(attributes, "method", request.getJsonrpcMethod());
        put(attributes, "jsonrpcId", request.getJsonrpcId());
        put(attributes, "toolName", request.getToolName());
        put(attributes, "principalId", request.getPrincipalId());
        put(attributes, "mcpClientId", request.getMcpClientId());
        put(attributes, "mcpSessionId", request.getMcpSessionId());
        put(attributes, "traceId", request.getTraceId());
        String name = request.getToolName() != null ? request.getJsonrpcMethod() + " " + request.getToolName()
                : request.getJsonrpcMethod();
        TraceNodeDto root = new TraceNodeDto(MCP_REQUEST, request.getId(), name, request.getReceivedAt(),
                request.getCompletedAt(), 0, millis(request.getReceivedAt(), request.getCompletedAt()),
                request.getStatus().name(), request.getErrorCode(), attributes, children);
        return new TraceTreeDto(root, summary(root.durationMs(), List.of(), tools));
    }

    private static TraceNodeDto modelNode(ModelCall call, Instant origin, List<TraceNodeDto> children) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        put(attributes, "purpose", call.getPurpose());
        put(attributes, "provider", call.getProvider());
        put(attributes, "model", call.getModel());
        put(attributes, "streaming", call.isStreaming());
        put(attributes, "seq", call.getSeq());
        put(attributes, "inputTokens", call.getInputTokens());
        put(attributes, "outputTokens", call.getOutputTokens());
        put(attributes, "cachedInputTokens", call.getCachedInputTokens());
        put(attributes, "costMicros", call.getCostMicros());
        put(attributes, "currency", call.getCurrency());
        put(attributes, "finishReason", call.getFinishReason());
        put(attributes, "timeToFirstTokenMs", call.getTimeToFirstTokenMs());
        put(attributes, "fallbackOfId", call.getFallbackOfId());
        return new TraceNodeDto(MODEL_CALL, call.getId(), call.getProvider() + "/" + call.getModel(),
                call.getStartedAt(), call.getEndedAt(), offset(origin, call.getStartedAt()),
                millis(call.getStartedAt(), call.getEndedAt()), call.getOutcome().name(), call.getErrorCode(),
                attributes, children);
    }

    private static TraceNodeDto toolNode(ToolInvocation tool, Instant origin) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        put(attributes, "channel", tool.getChannel());
        put(attributes, "elementRef", tool.getElementRef().toString());
        put(attributes, "accessMode", tool.getAccessMode());
        put(attributes, "argsHash", tool.getArgsHash());
        put(attributes, "argsRedacted", tool.getArgsRedactedJson());
        put(attributes, "rowCount", tool.getRowCount());
        put(attributes, "resultHash", tool.getResultHash());
        put(attributes, "truncated", tool.isTruncated());
        put(attributes, "writeViolation", tool.isWriteViolation());
        put(attributes, "proposalId", tool.getProposalId());
        put(attributes, "modelCallId", tool.getModelCallId());
        put(attributes, "providerToolCallId", tool.getProviderToolCallId());
        return new TraceNodeDto(TOOL_CALL, tool.getId(), tool.getToolName(), tool.getStartedAt(), tool.getEndedAt(),
                offset(origin, tool.getStartedAt()), millis(tool.getStartedAt(), tool.getEndedAt()),
                tool.getStatus().name(), tool.getErrorCode(), attributes, List.of());
    }

    private static TraceSummaryDto summary(long durationMs, List<ModelCall> calls, List<ToolInvocation> tools) {
        long in = 0;
        long out = 0;
        long cost = 0;
        Set<String> currencies = new HashSet<>();
        for (ModelCall call : calls) {
            in += call.getInputTokens();
            out += call.getOutputTokens();
            cost += call.getCostMicros();
            if (call.getCurrency() != null) {
                currencies.add(call.getCurrency());
            }
        }
        int problems = 0;
        int denied = 0;
        int violations = 0;
        for (ToolInvocation tool : tools) {
            ToolInvocationStatus status = tool.getStatus();
            if (status == ToolInvocationStatus.ERROR || status == ToolInvocationStatus.TIMEOUT
                    || status == ToolInvocationStatus.UNAVAILABLE) {
                problems++;
            }
            if (status == ToolInvocationStatus.NOT_PERMITTED) {
                denied++;
            }
            if (tool.isWriteViolation()) {
                violations++;
            }
        }
        return new TraceSummaryDto(durationMs, calls.size(), tools.size(), problems, denied, violations, in, out, cost,
                currencies.size() == 1 ? currencies.iterator().next() : null, currencies.size() > 1);
    }

    private static long offset(Instant origin, Instant at) {
        return Math.max(0, Duration.between(origin, at).toMillis());
    }

    private static long millis(Instant from, Instant to) {
        return Math.max(0, Duration.between(from, to).toMillis());
    }

    private static void put(Map<String, Object> attributes, String key, @Nullable Object value) {
        if (value == null) {
            return;
        }
        attributes.put(key, value instanceof Enum<?> e ? e.name() : value);
    }
}
