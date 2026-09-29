package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.persistence.support.PageRequest;
import com.springaimcpservercommon.persistence.support.Slice;
import com.springaimcpservercommon.persistence.support.TimeRange;
import com.springaimcpservercommon.persistence.telemetry.AgentTurn;
import com.springaimcpservercommon.persistence.telemetry.ModelCall;
import com.springaimcpservercommon.persistence.telemetry.TelemetryStore;
import com.springaimcpservercommon.persistence.telemetry.ToolInvocation;
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
import java.util.Objects;
import java.util.UUID;

/**
 * Trace viewer API (F-72): agent turns of a workspace with their model calls and tool invocations. Requires
 * {@link Permission#AUDIT_READ} in the workspace.
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
     * @param from        inclusive ISO-8601 start; default 24 h before {@code to}
     * @param to          exclusive ISO-8601 end; default now
     * @param limit       page size (1..200, default 50)
     * @param offset      page offset
     * @param request     current request
     * @return 200 with a page
     */
    @GetMapping("/turns")
    public ResponseEntity<?> turns(@PathVariable UUID workspaceId,
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
        return ResponseEntity.ok(PageDto.of(store.turnsOfWorkspace(workspaceId, range, page), TurnDto::of, range));
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
     * @param violationsOnly only invocations vetoed by the AI write guard
     * @param from           inclusive ISO-8601 start
     * @param to             exclusive ISO-8601 end
     * @param limit          page size
     * @param offset         page offset
     * @param request        current request
     * @return 200 with a page
     */
    @GetMapping("/tool-invocations")
    public ResponseEntity<?> toolInvocations(@PathVariable UUID workspaceId,
                                             @RequestParam(required = false, defaultValue = "false") boolean violationsOnly,
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
        return ResponseEntity.ok(PageDto.of(store.toolInvocationsOfWorkspace(workspaceId, range, violationsOnly, page),
                ToolInvocationDto::of, range));
    }
}
