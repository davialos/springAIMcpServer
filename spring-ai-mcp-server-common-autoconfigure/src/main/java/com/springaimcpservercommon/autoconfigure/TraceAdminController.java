package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.invocation.Channel;
import com.springaimcpservercommon.persistence.support.PageRequest;
import com.springaimcpservercommon.persistence.support.Slice;
import com.springaimcpservercommon.persistence.support.TimeRange;
import com.springaimcpservercommon.persistence.telemetry.AgentTurn;
import com.springaimcpservercommon.persistence.telemetry.McpRequest;
import com.springaimcpservercommon.persistence.telemetry.McpRequestFilter;
import com.springaimcpservercommon.persistence.telemetry.McpRequestStatus;
import com.springaimcpservercommon.persistence.telemetry.ModelCall;
import com.springaimcpservercommon.persistence.telemetry.TelemetryStore;
import com.springaimcpservercommon.persistence.telemetry.ToolInvocation;
import com.springaimcpservercommon.persistence.telemetry.ToolInvocationFilter;
import com.springaimcpservercommon.persistence.telemetry.ToolInvocationStatus;
import com.springaimcpservercommon.persistence.telemetry.ToolStat;
import com.springaimcpservercommon.persistence.telemetry.TurnFilter;
import com.springaimcpservercommon.persistence.telemetry.TurnOutcome;
import com.springaimcpservercommon.security.permission.Permission;
import com.springaimcpservercommon.webmvc.problem.ProblemCode;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import java.util.Objects;
import java.util.UUID;

/**
 * Trace viewer API (F-72): agent turns of a workspace with their model calls and tool invocations. Requires
 * {@link Permission#AUDIT_READ} in the workspace.
 *
 * <p>Beyond the flat lists it offers the call tree of a turn or MCP request with timings relative to the root, MCP
 * request traces, per-tool statistics and a lookup by OpenTelemetry trace id, so a trace seen in an external tracing
 * backend can be followed back to the store.
 *
 * <p>Only what the telemetry tables hold is exposed: tool arguments appear in their redacted form, results
 * only as row counts and hashes, and conversation content is never returned here (see the conversation API).
 * Every list is windowed and paged; a turn lookup is limited to a window because the telemetry tables are
 * partitioned by time.
 *
 * <p>Not a {@code @Component}; registered by {@link DaiAdminAutoConfiguration}.
 */
@NullMarked
@RequestMapping("/dynamic-ai/admin/api/v1/workspaces/{workspaceId}/traces")
public final class TraceAdminController {

    /** Default look-back for a single turn lookup. */
    static final Duration TURN_LOOKBACK = Duration.ofDays(7);

    private static final Pattern TOOL_NAME = Pattern.compile("^[a-z][a-z0-9_]{2,63}$");
    private static final Pattern RPC_METHOD = Pattern.compile("^[a-z][A-Za-z/_]{1,63}$");
    private static final Pattern TRACE_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    /**
     * Turn row.
     *
     * @param id                  turn id
     * @param startedAt           start
     * @param endedAt             end
     * @param conversationId      conversation, if any
     * @param agentResourceId     agent resource, if any
     * @param agentRevisionId     agent revision, if any
     * @param principalId         caller
     * @param channel             channel
     * @param traceId             trace correlation id
     * @param finishReason        why the turn ended
     * @param outcome             outcome
     * @param errorCode           error code, if any
     * @param timeToFirstTokenMs  time to first token, if streamed
     */
    public record TurnDto(UUID id, Instant startedAt, Instant endedAt, @Nullable UUID conversationId,
                          @Nullable UUID agentResourceId, @Nullable UUID agentRevisionId, UUID principalId,
                          String channel, @Nullable String traceId, String finishReason, String outcome,
                          @Nullable String errorCode, @Nullable Integer timeToFirstTokenMs) {
        static TurnDto of(AgentTurn t) {
            return new TurnDto(t.getId(), t.getStartedAt(), t.getEndedAt(), t.getConversationId(),
                    t.getAgentResourceId(), t.getAgentRevisionId(), t.getPrincipalId(), t.getChannel().name(),
                    t.getTraceId(), t.getFinishReason().name(), t.getOutcome().name(), t.getErrorCode(),
                    t.getTimeToFirstTokenMs());
        }
    }

    /**
     * Model call row.
     *
     * @param id                 call id
     * @param startedAt          start
     * @param endedAt            end
     * @param turnId             turn, if any
     * @param seq                position in the turn
     * @param purpose            purpose
     * @param provider           provider
     * @param model              model
     * @param streaming          whether streamed
     * @param timeToFirstTokenMs time to first token
     * @param inputTokens        input tokens
     * @param outputTokens       output tokens
     * @param cachedInputTokens  cached input tokens
     * @param costMicros         cost in currency micros
     * @param currency           currency
     * @param finishReason       provider finish reason
     * @param outcome            outcome
     * @param errorCode          error code, if any
     * @param fallbackOfId       call this one replaced after a failure, if any
     */
    public record ModelCallDto(UUID id, Instant startedAt, Instant endedAt, @Nullable UUID turnId,
                               @Nullable Short seq, String purpose, String provider, String model,
                               boolean streaming, @Nullable Integer timeToFirstTokenMs, int inputTokens,
                               int outputTokens, int cachedInputTokens, long costMicros, @Nullable String currency,
                               @Nullable String finishReason, String outcome, @Nullable String errorCode,
                               @Nullable UUID fallbackOfId) {
        static ModelCallDto of(ModelCall c) {
            return new ModelCallDto(c.getId(), c.getStartedAt(), c.getEndedAt(), c.getTurnId(), c.getSeq(),
                    c.getPurpose().name(), c.getProvider(), c.getModel(), c.isStreaming(),
                    c.getTimeToFirstTokenMs(), c.getInputTokens(), c.getOutputTokens(), c.getCachedInputTokens(),
                    c.getCostMicros(), c.getCurrency(), c.getFinishReason(), c.getOutcome().name(),
                    c.getErrorCode(), c.getFallbackOfId());
        }
    }

    /**
     * Tool invocation row. Arguments are the redacted form; results are counts and hashes only.
     *
     * @param id                 invocation id
     * @param startedAt          start
     * @param endedAt            end
     * @param channel            channel
     * @param turnId             turn, if any
     * @param modelCallId        model call that requested it, if any
     * @param principalId        caller
     * @param toolName           tool name
     * @param elementRef         catalog element
     * @param accessMode         read or propose
     * @param argsHash           hash of the arguments
     * @param argsRedactedJson   redacted arguments, if stored
     * @param status             status
     * @param rowCount           rows returned, if any
     * @param resultHash         hash of the result
     * @param truncated          whether the result was truncated
     * @param errorCode          error code, if any
     * @param writeViolation     whether the AI write guard vetoed a write
     * @param proposalId         resulting proposal, if any
     */
    public record ToolInvocationDto(UUID id, Instant startedAt, Instant endedAt, String channel,
                                    @Nullable UUID turnId, @Nullable UUID modelCallId, UUID principalId,
                                    String toolName, String elementRef, String accessMode, String argsHash,
                                    @Nullable String argsRedactedJson, String status, @Nullable Integer rowCount,
                                    @Nullable String resultHash, boolean truncated, @Nullable String errorCode,
                                    boolean writeViolation, @Nullable UUID proposalId) {
        static ToolInvocationDto of(ToolInvocation i) {
            return new ToolInvocationDto(i.getId(), i.getStartedAt(), i.getEndedAt(), i.getChannel().name(),
                    i.getTurnId(), i.getModelCallId(), i.getPrincipalId(), i.getToolName(),
                    i.getElementRef().toString(), i.getAccessMode().name(), i.getArgsHash(),
                    i.getArgsRedactedJson(), i.getStatus().name(), i.getRowCount(), i.getResultHash(),
                    i.isTruncated(), i.getErrorCode(), i.isWriteViolation(), i.getProposalId());
        }
    }

    /**
     * A page of rows.
     *
     * @param items   rows
     * @param limit   page size
     * @param offset  page offset
     * @param hasMore whether another page exists
     * @param from    inclusive window start
     * @param to      exclusive window end
     * @param <T>     row type
     */
    public record PageDto<T>(List<T> items, int limit, int offset, boolean hasMore, Instant from, Instant to) {
        static <S, T> PageDto<T> of(Slice<S> slice, java.util.function.Function<S, T> map, TimeRange range) {
            return new PageDto<>(slice.items().stream().map(map).toList(), slice.page().limit(),
                    slice.page().offset(), slice.hasMore(), range.from(), range.to());
        }
    }

    /**
     * One turn with everything it did.
     *
     * @param turn            the turn
     * @param modelCalls      model calls in order
     * @param toolInvocations tool invocations in order
     */
    public record TurnDetailDto(TurnDto turn, List<ModelCallDto> modelCalls,
                                List<ToolInvocationDto> toolInvocations) {}

    /**
     * One node of a call tree.
     *
     * @param kind       {@code TURN}, {@code MODEL_CALL}, {@code TOOL_CALL} or {@code MCP_REQUEST}
     * @param id         row id
     * @param name       display name (tool name, {@code provider/model}, JSON-RPC method)
     * @param startedAt  start
     * @param endedAt    end
     * @param offsetMs   start relative to the root, in milliseconds
     * @param durationMs duration in milliseconds
     * @param status     outcome or status of the node
     * @param errorCode  error code, if any
     * @param attributes kind-specific facts (tokens, cost, hashes, counts); never results or conversation content
     * @param children   nodes below this one, ordered by start
     */
    public record TraceNodeDto(String kind, UUID id, String name, Instant startedAt, Instant endedAt, long offsetMs,
                               long durationMs, String status, @Nullable String errorCode,
                               java.util.Map<String, Object> attributes, List<TraceNodeDto> children) {}

    /**
     * Totals of a call tree.
     *
     * @param durationMs      duration of the root
     * @param modelCalls      model calls
     * @param toolCalls       tool calls
     * @param toolProblems    tool calls that ended ERROR, TIMEOUT or UNAVAILABLE
     * @param notPermitted    tool calls refused by the permission re-check
     * @param writeViolations tool calls the AI write guard vetoed
     * @param inputTokens     input tokens over all model calls
     * @param outputTokens    output tokens over all model calls
     * @param costMicros      cost over all model calls, in currency micros
     * @param currency        the single currency of the calls, or {@code null} when there is none or several
     * @param mixedCurrency   whether the calls were priced in several currencies (the cost is then not comparable)
     */
    public record TraceSummaryDto(long durationMs, int modelCalls, int toolCalls, int toolProblems, int notPermitted,
                                  int writeViolations, long inputTokens, long outputTokens, long costMicros,
                                  @Nullable String currency, boolean mixedCurrency) {}

    /**
     * A call tree with its totals.
     *
     * @param root    the turn or MCP request
     * @param summary totals
     */
    public record TraceTreeDto(TraceNodeDto root, TraceSummaryDto summary) {}

    /**
     * MCP request row.
     *
     * @param id          request id
     * @param receivedAt  when it arrived
     * @param completedAt when the answer was ready
     * @param principalId caller
     * @param mcpClientId approved MCP client, if known
     * @param method      JSON-RPC method
     * @param jsonrpcId   JSON-RPC id, if any
     * @param toolName    tool called, if any
     * @param status      outcome
     * @param errorCode   error code, if any
     * @param traceId     trace correlation id, if any
     */
    public record McpRequestDto(UUID id, Instant receivedAt, Instant completedAt, UUID principalId,
                                @Nullable UUID mcpClientId, String method, @Nullable String jsonrpcId,
                                @Nullable String toolName, String status, @Nullable String errorCode,
                                @Nullable String traceId) {
        static McpRequestDto of(McpRequest r) {
            return new McpRequestDto(r.getId(), r.getReceivedAt(), r.getCompletedAt(), r.getPrincipalId(),
                    r.getMcpClientId(), r.getJsonrpcMethod(), r.getJsonrpcId(), r.getToolName(),
                    r.getStatus().name(), r.getErrorCode(), r.getTraceId());
        }
    }

    /**
     * Statistics of one tool.
     *
     * @param toolName        tool
     * @param calls           invocations
     * @param errors          invocations that ended ERROR, TIMEOUT or UNAVAILABLE
     * @param notPermitted    invocations refused by the permission re-check
     * @param writeViolations invocations the AI write guard vetoed
     * @param errorRate       {@code errors / calls}
     * @param avgMillis       mean duration in milliseconds
     * @param maxMillis       longest duration in milliseconds
     */
    public record ToolStatDto(String toolName, long calls, long errors, long notPermitted, long writeViolations,
                              double errorRate, double avgMillis, double maxMillis) {
        static ToolStatDto of(ToolStat t) {
            return new ToolStatDto(t.toolName(), t.calls(), t.errors(), t.notPermitted(), t.writeViolations(),
                    t.calls() == 0 ? 0.0 : (double) t.errors() / t.calls(), t.avgMillis(), t.maxMillis());
        }
    }

    /**
     * Tool statistics of a window.
     *
     * @param from  inclusive window start
     * @param to    exclusive window end
     * @param tools one row per tool, most used first
     */
    public record ToolStatsDto(Instant from, Instant to, List<ToolStatDto> tools) {}

    /**
     * Turns that carry a trace id.
     *
     * @param traceId the trace id looked up
     * @param turns   matching turns of the workspace, newest first
     */
    public record TraceLookupDto(String traceId, List<TurnDto> turns) {}

    private final TelemetryStore store;
    private final AdminApi api;
    private final Clock clock;

    TraceAdminController(TelemetryStore store, AdminApi api, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.api = Objects.requireNonNull(api, "api");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Turns of the workspace in a window, newest first.
     *
     * @param workspaceId workspace
     * @param principalId only turns of this caller
     * @param agentId     only turns of this agent (resource id)
     * @param outcome     only turns with this outcome (SUCCESS, FAILED, CANCELLED, REJECTED)
     * @param channel     only turns of this channel (CHAT, PLAYGROUND, MCP, ENDPOINT)
     * @param from        inclusive ISO-8601 start; default 24 h before {@code to}
     * @param to          exclusive ISO-8601 end; default now
     * @param limit       page size (1..200, default 50)
     * @param offset      page offset
     * @param request     current request
     * @return 200 with a page
     */
    @GetMapping("/turns")
    public ResponseEntity<?> turns(@PathVariable UUID workspaceId,
                                   @RequestParam(required = false) @Nullable UUID principalId,
                                   @RequestParam(required = false) @Nullable UUID agentId,
                                   @RequestParam(required = false) @Nullable String outcome,
                                   @RequestParam(required = false) @Nullable String channel,
                                   @RequestParam(required = false) @Nullable String from,
                                   @RequestParam(required = false) @Nullable String to,
                                   @RequestParam(required = false) @Nullable Integer limit,
                                   @RequestParam(required = false) @Nullable Integer offset,
                                   HttpServletRequest request) {
        var gate = api.gate(request, Permission.AUDIT_READ, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        TurnFilter filter = new TurnFilter(principalId, agentId, enumParam(TurnOutcome.class, outcome, "outcome"),
                enumParam(Channel.class, channel, "channel"));
        TimeRange range = AdminApi.window(from, to, clock.instant());
        PageRequest page = AdminApi.page(limit, offset);
        return ResponseEntity.ok(PageDto.of(store.turnsOfWorkspace(workspaceId, range, filter, page), TurnDto::of,
                range));
    }

    /**
     * One turn with its model calls and tool invocations.
     *
     * @param workspaceId workspace
     * @param turnId      turn
     * @param from        inclusive ISO-8601 start of the lookup window; default 7 days before {@code to}
     * @param to          exclusive ISO-8601 end; default now
     * @param request     current request
     * @return 200 with the detail; 404 when the turn is not in the window or belongs to another workspace
     */
    @GetMapping("/turns/{turnId}")
    public ResponseEntity<?> turn(@PathVariable UUID workspaceId, @PathVariable UUID turnId,
                                  @RequestParam(required = false) @Nullable String from,
                                  @RequestParam(required = false) @Nullable String to,
                                  HttpServletRequest request) {
        var gate = api.gate(request, Permission.AUDIT_READ, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        Instant now = clock.instant();
        Instant end = to == null || to.isBlank() ? now : AdminApi.instant(to, "to");
        Instant start = from == null || from.isBlank() ? end.minus(TURN_LOOKBACK) : AdminApi.instant(from, "from");
        AgentTurn turn = store.findTurn(turnId, new TimeRange(start, end)).orElse(null);
        if (turn == null || !turn.getWorkspaceId().equals(workspaceId)) {
            return AdminApi.problem(ProblemCode.NOT_FOUND, "Turn not found", null, request);
        }
        return ResponseEntity.ok(new TurnDetailDto(TurnDto.of(turn),
                store.modelCallsOfTurn(turnId).stream().map(ModelCallDto::of).toList(),
                store.toolInvocationsOfTurn(turnId).stream().map(ToolInvocationDto::of).toList()));
    }

    /**
     * Model calls of the workspace in a window, newest first.
     *
     * @param workspaceId workspace
     * @param from        inclusive ISO-8601 start
     * @param to          exclusive ISO-8601 end
     * @param limit       page size
     * @param offset      page offset
     * @param request     current request
     * @return 200 with a page
     */
    @GetMapping("/model-calls")
    public ResponseEntity<?> modelCalls(@PathVariable UUID workspaceId,
                                        @RequestParam(required = false) @Nullable String from,
                                        @RequestParam(required = false) @Nullable String to,
                                        @RequestParam(required = false) @Nullable Integer limit,
                                        @RequestParam(required = false) @Nullable Integer offset,
                                        HttpServletRequest request) {
        var gate = api.gate(request, Permission.AUDIT_READ, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        TimeRange range = AdminApi.window(from, to, clock.instant());
        PageRequest page = AdminApi.page(limit, offset);
        return ResponseEntity.ok(PageDto.of(store.modelCallsOfWorkspace(workspaceId, range, page),
                ModelCallDto::of, range));
    }

    /**
     * Tool invocations of the workspace in a window, newest first.
     *
     * @param workspaceId    workspace
     * @param toolName       only this tool
     * @param status         only this status (OK, EMPTY, TRUNCATED, ERROR, NOT_PERMITTED, UNAVAILABLE, PROPOSED, TIMEOUT)
     * @param principalId    only calls made as this caller
     * @param violationsOnly only invocations vetoed by the AI write guard
     * @param problemsOnly   only invocations whose status is not OK or EMPTY
     * @param from           inclusive ISO-8601 start
     * @param to             exclusive ISO-8601 end
     * @param limit          page size
     * @param offset         page offset
     * @param request        current request
     * @return 200 with a page
     */
    @GetMapping("/tool-invocations")
    public ResponseEntity<?> toolInvocations(@PathVariable UUID workspaceId,
                                             @RequestParam(required = false) @Nullable String toolName,
                                             @RequestParam(required = false) @Nullable String status,
                                             @RequestParam(required = false) @Nullable UUID principalId,
                                             @RequestParam(required = false, defaultValue = "false") boolean violationsOnly,
                                             @RequestParam(required = false, defaultValue = "false") boolean problemsOnly,
                                             @RequestParam(required = false) @Nullable String from,
                                             @RequestParam(required = false) @Nullable String to,
                                             @RequestParam(required = false) @Nullable Integer limit,
                                             @RequestParam(required = false) @Nullable Integer offset,
                                             HttpServletRequest request) {
        var gate = api.gate(request, Permission.AUDIT_READ, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        ToolInvocationFilter filter = new ToolInvocationFilter(matching(TOOL_NAME, toolName, "toolName"),
                enumParam(ToolInvocationStatus.class, status, "status"), principalId, violationsOnly, problemsOnly);
        TimeRange range = AdminApi.window(from, to, clock.instant());
        PageRequest page = AdminApi.page(limit, offset);
        return ResponseEntity.ok(PageDto.of(store.toolInvocationsOfWorkspace(workspaceId, range, filter, page),
                ToolInvocationDto::of, range));
    }

    /**
     * The call tree of a turn: the turn, its model calls and, under the model call that requested it (or directly
     * under the turn), every tool call, with timings relative to the start of the turn and totals.
     *
     * @param workspaceId workspace
     * @param turnId      turn
     * @param from        inclusive ISO-8601 start of the lookup window; default 7 days before {@code to}
     * @param to          exclusive ISO-8601 end; default now
     * @param request     current request
     * @return 200 with the tree; 404 when the turn is not in the window or belongs to another workspace
     */
    @GetMapping("/turns/{turnId}/tree")
    public ResponseEntity<?> turnTree(@PathVariable UUID workspaceId, @PathVariable UUID turnId,
                                      @RequestParam(required = false) @Nullable String from,
                                      @RequestParam(required = false) @Nullable String to,
                                      HttpServletRequest request) {
        var gate = api.gate(request, Permission.AUDIT_READ, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        TimeRange range = lookupWindow(from, to);
        AgentTurn turn = store.findTurn(turnId, range).orElse(null);
        if (turn == null || !turn.getWorkspaceId().equals(workspaceId)) {
            return AdminApi.problem(ProblemCode.NOT_FOUND, "Turn not found", null, request);
        }
        return ResponseEntity.ok(TraceTreeBuilder.turn(turn, store.modelCallsOfTurn(turnId),
                store.toolInvocationsOfTurn(turnId)));
    }

    /**
     * Turns of the workspace that carry an OpenTelemetry trace id, to follow a trace seen in an external tracing
     * backend back to the store.
     *
     * @param workspaceId workspace
     * @param traceId     trace id ({@code A-Za-z0-9._:-}, up to 128 characters)
     * @param from        inclusive ISO-8601 start; default 7 days before {@code to}
     * @param to          exclusive ISO-8601 end; default now
     * @param request     current request
     * @return 200 with the matching turns (possibly none)
     */
    @GetMapping("/by-trace-id/{traceId}")
    public ResponseEntity<?> byTraceId(@PathVariable UUID workspaceId, @PathVariable String traceId,
                                       @RequestParam(required = false) @Nullable String from,
                                       @RequestParam(required = false) @Nullable String to,
                                       HttpServletRequest request) {
        var gate = api.gate(request, Permission.AUDIT_READ, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        String id = matching(TRACE_ID, traceId, "traceId");
        if (id == null) {
            throw new IllegalArgumentException("traceId has an invalid format");
        }
        List<TurnDto> turns = store.turnsOfTrace(id, lookupWindow(from, to)).stream()
                .filter(t -> t.getWorkspaceId().equals(workspaceId)).map(TurnDto::of).toList();
        return ResponseEntity.ok(new TraceLookupDto(id, turns));
    }

    /**
     * MCP requests of the workspace in a window, newest first.
     *
     * @param workspaceId workspace
     * @param method      only this JSON-RPC method (for example {@code tools/call})
     * @param toolName    only calls of this tool
     * @param status      only this status (OK, ERROR, DENIED, RATE_LIMITED)
     * @param principalId only requests of this caller
     * @param from        inclusive ISO-8601 start
     * @param to          exclusive ISO-8601 end
     * @param limit       page size
     * @param offset      page offset
     * @param request     current request
     * @return 200 with a page
     */
    @GetMapping("/mcp-requests")
    public ResponseEntity<?> mcpRequests(@PathVariable UUID workspaceId,
                                         @RequestParam(required = false) @Nullable String method,
                                         @RequestParam(required = false) @Nullable String toolName,
                                         @RequestParam(required = false) @Nullable String status,
                                         @RequestParam(required = false) @Nullable UUID principalId,
                                         @RequestParam(required = false) @Nullable String from,
                                         @RequestParam(required = false) @Nullable String to,
                                         @RequestParam(required = false) @Nullable Integer limit,
                                         @RequestParam(required = false) @Nullable Integer offset,
                                         HttpServletRequest request) {
        var gate = api.gate(request, Permission.AUDIT_READ, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        McpRequestFilter filter = new McpRequestFilter(matching(RPC_METHOD, method, "method"),
                matching(TOOL_NAME, toolName, "toolName"), enumParam(McpRequestStatus.class, status, "status"),
                principalId);
        TimeRange range = AdminApi.window(from, to, clock.instant());
        PageRequest page = AdminApi.page(limit, offset);
        return ResponseEntity.ok(PageDto.of(store.mcpRequestsOfWorkspace(workspaceId, range, filter, page),
                McpRequestDto::of, range));
    }

    /**
     * One MCP request with the tool calls it made, as a call tree.
     *
     * @param workspaceId workspace
     * @param requestId   MCP request
     * @param from        inclusive ISO-8601 start of the lookup window; default 7 days before {@code to}
     * @param to          exclusive ISO-8601 end; default now
     * @param request     current request
     * @return 200 with the tree; 404 when the request is not in the window or belongs to another workspace
     */
    @GetMapping("/mcp-requests/{requestId}")
    public ResponseEntity<?> mcpRequest(@PathVariable UUID workspaceId, @PathVariable UUID requestId,
                                        @RequestParam(required = false) @Nullable String from,
                                        @RequestParam(required = false) @Nullable String to,
                                        HttpServletRequest request) {
        var gate = api.gate(request, Permission.AUDIT_READ, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        McpRequest mcp = store.findMcpRequest(requestId, lookupWindow(from, to)).orElse(null);
        if (mcp == null || !workspaceId.equals(mcp.getWorkspaceId())) {
            return AdminApi.problem(ProblemCode.NOT_FOUND, "MCP request not found", null, request);
        }
        List<ToolInvocation> tools = store.toolInvocationsOfMcpRequest(requestId).stream()
                .filter(t -> t.getWorkspaceId().equals(workspaceId)).toList();
        return ResponseEntity.ok(TraceTreeBuilder.mcpRequest(mcp, tools));
    }

    /**
     * Per-tool statistics of the workspace in a window: calls, errors, refusals, write-guard vetoes and durations.
     *
     * @param workspaceId workspace
     * @param from        inclusive ISO-8601 start; default 24 h before {@code to}
     * @param to          exclusive ISO-8601 end; default now
     * @param limit       most tools returned (1..200, default 50)
     * @param request     current request
     * @return 200 with the statistics, most used tool first
     */
    @GetMapping("/tool-stats")
    public ResponseEntity<?> toolStats(@PathVariable UUID workspaceId,
                                       @RequestParam(required = false) @Nullable String from,
                                       @RequestParam(required = false) @Nullable String to,
                                       @RequestParam(required = false) @Nullable Integer limit,
                                       HttpServletRequest request) {
        var gate = api.gate(request, Permission.AUDIT_READ, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        TimeRange range = AdminApi.window(from, to, clock.instant());
        int max = AdminApi.page(limit, null).limit();
        return ResponseEntity.ok(new ToolStatsDto(range.from(), range.to(),
                store.toolStats(workspaceId, range, max).stream().map(ToolStatDto::of).toList()));
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────────────────

    private TimeRange lookupWindow(@Nullable String from, @Nullable String to) {
        Instant end = to == null || to.isBlank() ? clock.instant() : AdminApi.instant(to, "to");
        Instant start = from == null || from.isBlank() ? end.minus(TURN_LOOKBACK) : AdminApi.instant(from, "from");
        return new TimeRange(start, end);
    }

    /** Parses an optional enum query parameter; the message names the parameter and the allowed values only. */
    static <E extends Enum<E>> @Nullable E enumParam(Class<E> type, @Nullable String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + " must be one of "
                    + java.util.Arrays.toString(type.getEnumConstants()));
        }
    }

    /** Validates an optional query parameter against a pattern; the message never echoes the value. */
    static @Nullable String matching(Pattern pattern, @Nullable String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        if (!pattern.matcher(v).matches()) {
            throw new IllegalArgumentException(name + " has an invalid format");
        }
        return v;
    }
}
