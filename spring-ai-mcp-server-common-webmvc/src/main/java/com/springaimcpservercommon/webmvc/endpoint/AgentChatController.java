package com.springaimcpservercommon.webmvc.endpoint;

import com.springaimcpservercommon.ai.agent.AgentDefinition;
import com.springaimcpservercommon.ai.runtime.AgentInvoker;
import com.springaimcpservercommon.ai.runtime.StreamEvent;
import com.springaimcpservercommon.core.json.CanonicalJson;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.security.authz.AuthorizationEngine;
import com.springaimcpservercommon.security.authz.AuthorizationOutcome;
import com.springaimcpservercommon.security.authz.AuthorizationRequest;
import com.springaimcpservercommon.security.authz.ResourceRef;
import com.springaimcpservercommon.webmvc.problem.ProblemCode;
import com.springaimcpservercommon.webmvc.problem.ProblemDetailFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP controller for agent chat turns: synchronous and SSE streaming (LLD-06 §4, LLD-13).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /dynamic-ai/api/agents/{slug}/chat} → synchronous JSON turn result</li>
 *   <li>{@code POST /dynamic-ai/api/agents/{slug}/chat/stream} → SSE stream of {@link StreamEvent}</li>
 *   <li>{@code GET /dynamic-ai/api/agents/{slug}/turns/{turnId}/events} → SSE replay from ring buffer (LLD-13 §5)</li>
 * </ul>
 *
 * <p>The streaming endpoint:
 * <ul>
 *   <li>All pre-checks (authZ, rate limit, kill switch) run synchronously before the stream opens.
 *       Failures before the stream return ordinary 4xx/5xx problem responses (LLD-13 §3).</li>
 *   <li>Heartbeats are SSE comments ({@code : keep-alive}), not events, bounded by
 *       {@code takeUntilOther} so they stop when the content stream completes (LLD-13 §4).</li>
 *   <li>Error events carry only a stable {@code code} — never exception messages (LLD-13 §4).</li>
 *   <li>Response headers disable caching and proxy buffering (LLD-13 §2).</li>
 * </ul>
 *
 * <p>This class is not a {@code @Component} — it is registered as a bean by the
 * {@code autoconfigure} module. Spring MVC detects it via its {@link RequestMapping} annotation.
 */
@NullMarked
@RequestMapping("/dynamic-ai/api/agents/{slug}")
public class AgentChatController {

    private static final Logger LOG = LoggerFactory.getLogger(AgentChatController.class);
    private static final String CONTENT_TYPE_PROBLEM = "application/problem+json;charset=UTF-8";
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    private static final int MAX_BUFFERED_EVENTS = 256;

    /**
     * Port: resolves a published {@link AgentDefinition} by slug within the current workspace context.
     */
    @FunctionalInterface
    public interface AgentResolver {
        /**
         * @param slug  the agent's URL slug
         * @return the definition, or {@code null} if not found or not published
         */
        @Nullable AgentDefinition resolve(String slug);
    }

    /**
     * Inbound request body for both chat endpoints.
     *
     * @param conversationId existing conversation to continue; omit to start a new one
     * @param message        user message text
     * @param clientRequestId client-generated idempotency key
     */
    public record ChatRequest(
            @Nullable UUID conversationId,
            String message,
            @Nullable String clientRequestId) {}

    private final AgentResolver agentResolver;
    private final AgentInvoker agentInvoker;
    private final GenericDynamicHandler.DaiPrincipalResolver principalResolver;
    private final AuthorizationEngine authorizationEngine;
    private final GenericDynamicHandler.RateLimiter rateLimiter;
    private final GenericDynamicHandler.KillSwitchChecker killSwitchChecker;
    private final @Nullable TurnEventBuffer turnEventBuffer;

    /**
     * Creates the controller with all required ports, without SSE replay.
     *
     * @param agentResolver       looks up the published agent by slug
     * @param agentInvoker        executes agent turns (sync and stream)
     * @param principalResolver   maps the HTTP request to a {@link DaiPrincipal}
     * @param authorizationEngine authorizes the invocation
     * @param rateLimiter         per-principal rate limit enforcement
     * @param killSwitchChecker   checks the agent kill switch
     */
    public AgentChatController(AgentResolver agentResolver,
                                AgentInvoker agentInvoker,
                                GenericDynamicHandler.DaiPrincipalResolver principalResolver,
                                AuthorizationEngine authorizationEngine,
                                GenericDynamicHandler.RateLimiter rateLimiter,
                                GenericDynamicHandler.KillSwitchChecker killSwitchChecker) {
        this(agentResolver, agentInvoker, principalResolver, authorizationEngine,
             rateLimiter, killSwitchChecker, null);
    }

    /**
     * Creates the controller with all required ports and optional SSE replay support.
     *
     * @param agentResolver       looks up the published agent by slug
     * @param agentInvoker        executes agent turns (sync and stream)
     * @param principalResolver   maps the HTTP request to a {@link DaiPrincipal}
     * @param authorizationEngine authorizes the invocation
     * @param rateLimiter         per-principal rate limit enforcement
     * @param killSwitchChecker   checks the agent kill switch
     * @param turnEventBuffer     optional ring buffer enabling SSE replay via
     *                            {@code GET /turns/{turnId}/events}; {@code null} disables replay
     */
    public AgentChatController(AgentResolver agentResolver,
                                AgentInvoker agentInvoker,
                                GenericDynamicHandler.DaiPrincipalResolver principalResolver,
                                AuthorizationEngine authorizationEngine,
                                GenericDynamicHandler.RateLimiter rateLimiter,
                                GenericDynamicHandler.KillSwitchChecker killSwitchChecker,
                                @Nullable TurnEventBuffer turnEventBuffer) {
        this.agentResolver = Objects.requireNonNull(agentResolver, "agentResolver");
        this.agentInvoker = Objects.requireNonNull(agentInvoker, "agentInvoker");
        this.principalResolver = Objects.requireNonNull(principalResolver, "principalResolver");
        this.authorizationEngine = Objects.requireNonNull(authorizationEngine, "authorizationEngine");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.killSwitchChecker = Objects.requireNonNull(killSwitchChecker, "killSwitchChecker");
        this.turnEventBuffer = turnEventBuffer;
    }

    // ─── Sync endpoint ───────────────────────────────────────────────────────

    /**
     * Synchronous agent turn. Blocks until the model produces its final answer.
     *
     * @return {@code 200 application/json} with the turn result, or a problem response on error
     */
    @PostMapping(
            value = "/chat",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> chat(
            @PathVariable String slug,
            @RequestBody ChatRequest body,
            HttpServletRequest httpRequest) {

        // Pre-checks
        var preCheck = runPreChecks(slug, body, httpRequest);
        if (preCheck.problem() != null) {
            return ResponseEntity.status(preCheck.problemStatus())
                    .contentType(MediaType.parseMediaType(CONTENT_TYPE_PROBLEM))
                    .body(preCheck.problem());
        }

        AgentDefinition agent = preCheck.agent();
        DaiPrincipal principal = preCheck.principal();
        Authentication auth = preCheck.authentication();

        try {
            var request = new AgentInvoker.AgentChatRequest(
                    body.conversationId(), body.message(),
                    body.clientRequestId() != null ? body.clientRequestId() : UUID.randomUUID().toString());

            AgentInvoker.SyncChatResult result = agentInvoker.invoke(agent, request, principal, auth);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(syncResultToJson(result));

        } catch (AgentInvoker.AgentInvocationException e) {
            LOG.warn("Agent {} invocation rejected for principal {}: {} {}",
                    slug, principal.principalId(), e.code(), e.getMessage());
            String problem = ProblemDetailFactory.build(
                    mapAgentCode(e.code()), e.getMessage(), null, httpRequest.getRequestURI());
            return ResponseEntity.status(mapAgentCode(e.code()).httpStatus())
                    .contentType(MediaType.parseMediaType(CONTENT_TYPE_PROBLEM))
                    .body(problem);
        } catch (Exception e) {
            LOG.error("Unexpected error in sync chat for agent {}", slug, e);
            String problem = ProblemDetailFactory.build(
                    ProblemCode.INTERNAL_ERROR, "An unexpected error occurred.", null,
                    httpRequest.getRequestURI());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.parseMediaType(CONTENT_TYPE_PROBLEM))
                    .body(problem);
        }
    }

    // ─── Streaming endpoint ──────────────────────────────────────────────────

    /**
     * Streaming agent turn. Returns a cold SSE stream per LLD-13.
     *
     * <p>Pre-checks run synchronously before any response is sent. After the stream opens
     * (200 OK), errors are delivered as terminal {@link StreamEvent.ErrorEvent} events.
     *
     * @return {@code 200 text/event-stream} on success, or a problem response on pre-check failure
     */
    @PostMapping(
            value = "/chat/stream",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Object chatStream(
            @PathVariable String slug,
            @RequestBody ChatRequest body,
            HttpServletRequest httpRequest) {

        // Pre-checks (synchronous — failures become 4xx/5xx before opening the stream)
        var preCheck = runPreChecks(slug, body, httpRequest);
        if (preCheck.problem() != null) {
            return ResponseEntity.status(preCheck.problemStatus())
                    .contentType(MediaType.parseMediaType(CONTENT_TYPE_PROBLEM))
                    .body(preCheck.problem());
        }

        AgentDefinition agent = preCheck.agent();
        DaiPrincipal principal = preCheck.principal();
        Authentication auth = preCheck.authentication();
        UUID turnId = UUID.randomUUID();

        var chatRequest = new AgentInvoker.AgentChatRequest(
                body.conversationId(), body.message(),
                body.clientRequestId() != null ? body.clientRequestId() : UUID.randomUUID().toString());

        // Sequence counter for SSE event ids
        AtomicInteger seq = new AtomicInteger(0);

        // Content stream: agent events mapped to SSE (and optionally buffered for replay)
        Flux<ServerSentEvent<String>> content = agentInvoker
                .stream(agent, chatRequest, principal, auth)
                .map(event -> {
                    int currentSeq = seq.getAndIncrement();
                    if (turnEventBuffer != null) {
                        turnEventBuffer.append(turnId, currentSeq, event);
                    }
                    return toSse(event, turnId, currentSeq);
                })
                .timeout(
                        Duration.ofSeconds(20),   // first-token timeout
                        Flux.just(errorEvent(turnId, "model-timeout", "The model did not respond in time.", false, seq.get())))
                .onBackpressureBuffer(MAX_BUFFERED_EVENTS,
                        dropped -> LOG.warn("Agent {} stream: client too slow, dropping event for turn {}",
                                agent.slug(), turnId))
                .onErrorResume(e -> {
                    LOG.error("Agent {} stream error for turn {}", agent.slug(), turnId, e);
                    return Flux.just(errorEvent(turnId, "stream-error",
                            "An error occurred while streaming the response.", false, seq.get()));
                })
                .doFinally(signal -> {
                    if (turnEventBuffer != null) {
                        turnEventBuffer.complete(turnId);
                    }
                });

        // Heartbeat stream: SSE comments, bounded by content lifecycle
        Flux<ServerSentEvent<String>> heartbeat = Flux
                .interval(HEARTBEAT_INTERVAL)
                .map(tick -> ServerSentEvent.<String>builder()
                        .comment("keep-alive")
                        .build())
                .takeUntilOther(content.ignoreElements()); // STOPS when content completes or errors

        // Full stream: content merged with heartbeats
        Flux<ServerSentEvent<String>> stream = content.mergeWith(heartbeat);

        // Response headers per LLD-13 §2
        HttpHeaders headers = new HttpHeaders();
        headers.add("Cache-Control", "no-cache, no-transform");
        headers.add("X-Accel-Buffering", "no");

        return ResponseEntity.ok()
                .headers(headers)
                .body(stream);
    }

    // ─── SSE replay endpoint ─────────────────────────────────────────────────

    /**
     * SSE replay endpoint: replays buffered events for a completed or in-progress turn.
     *
     * <p>The client supplies the SSE {@code Last-Event-ID} header (format: {@code {turnId}:{seq}})
     * to resume from the last received event. When absent, all buffered events are replayed.
     *
     * <p>Returns 404 when the turn is unknown or has expired beyond the replay window.
     * Returns 503 when no {@link TurnEventBuffer} is configured.
     *
     * @return {@code 200 text/event-stream} with buffered events, or a problem response on failure
     */
    @GetMapping(
            value = "/turns/{turnId}/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Object replayTurnStream(
            @PathVariable String slug,
            @PathVariable UUID turnId,
            HttpServletRequest httpRequest) {

        // 1. Resolve agent
        AgentDefinition agent = agentResolver.resolve(slug);
        if (agent == null) {
            return ResponseEntity.status(404)
                    .contentType(MediaType.parseMediaType(CONTENT_TYPE_PROBLEM))
                    .body(ProblemDetailFactory.build(ProblemCode.NOT_FOUND,
                            "Agent not found", "No published agent with slug: " + slug,
                            httpRequest.getRequestURI()));
        }

        // 2. Principal
        DaiPrincipal principal;
        Authentication auth;
        try {
            principal = principalResolver.resolve(httpRequest);
            auth = SecurityContextHolder.getContext().getAuthentication();
        } catch (Exception e) {
            return ResponseEntity.status(403)
                    .contentType(MediaType.parseMediaType(CONTENT_TYPE_PROBLEM))
                    .body(ProblemDetailFactory.build(ProblemCode.ACCESS_DENIED,
                            "Authentication required", null, httpRequest.getRequestURI()));
        }
        if (auth == null) {
            return ResponseEntity.status(403)
                    .contentType(MediaType.parseMediaType(CONTENT_TYPE_PROBLEM))
                    .body(ProblemDetailFactory.build(ProblemCode.ACCESS_DENIED,
                            "Authentication required", null, httpRequest.getRequestURI()));
        }

        // 3. Authorization (same permission required as for the original stream)
        var resource = com.springaimcpservercommon.security.authz.ResourceRef.of(
                agent.workspaceId(), agent.id(),
                com.springaimcpservercommon.annotations.Classification.PUBLIC);
        var authReq = com.springaimcpservercommon.security.authz.AuthorizationRequest.onResource(
                principal,
                com.springaimcpservercommon.security.permission.Permission.ENDPOINT_INVOKE,
                resource);
        if (authorizationEngine.decide(authReq) instanceof AuthorizationOutcome.Deny) {
            return ResponseEntity.status(403)
                    .contentType(MediaType.parseMediaType(CONTENT_TYPE_PROBLEM))
                    .body(ProblemDetailFactory.build(ProblemCode.ACCESS_DENIED,
                            "Access denied", null, httpRequest.getRequestURI()));
        }

        // 4. Check replay capability
        if (turnEventBuffer == null) {
            return ResponseEntity.status(503)
                    .contentType(MediaType.parseMediaType(CONTENT_TYPE_PROBLEM))
                    .body(ProblemDetailFactory.build(ProblemCode.ENDPOINT_DISABLED,
                            "Stream replay not available",
                            "No turn event buffer is configured for this deployment.",
                            httpRequest.getRequestURI()));
        }

        // 5. Parse Last-Event-ID → afterSeq
        String lastEventId = httpRequest.getHeader("Last-Event-ID");
        int afterSeq = parseAfterSeq(lastEventId, turnId);

        // 6. Look up buffered events
        java.util.List<TurnEventBuffer.BufferedEvent> events = turnEventBuffer.since(turnId, afterSeq);
        if (events == null) {
            return ResponseEntity.status(404)
                    .contentType(MediaType.parseMediaType(CONTENT_TYPE_PROBLEM))
                    .body(ProblemDetailFactory.build(ProblemCode.NOT_FOUND,
                            "Turn not found",
                            "Turn " + turnId + " is unknown or the replay window has expired.",
                            httpRequest.getRequestURI()));
        }

        // 7. Build and return the replay stream
        Flux<ServerSentEvent<String>> replay = Flux.fromIterable(events)
                .map(e -> toSse(e.event(), turnId, e.seq()));

        HttpHeaders headers = new HttpHeaders();
        headers.add("Cache-Control", "no-cache, no-transform");
        headers.add("X-Accel-Buffering", "no");

        return ResponseEntity.ok().headers(headers).body(replay);
    }

    // ─── Pre-check pipeline ──────────────────────────────────────────────────

    private PreCheckResult runPreChecks(String slug, ChatRequest body, HttpServletRequest httpRequest) {
        // 1. Resolve agent
        AgentDefinition agent = agentResolver.resolve(slug);
        if (agent == null) {
            return PreCheckResult.problem(
                    ProblemDetailFactory.build(ProblemCode.NOT_FOUND,
                            "Agent not found", "No published agent with slug: " + slug,
                            httpRequest.getRequestURI()),
                    404);
        }

        // 2. Kill switch (isActive = true means the kill switch is ON, endpoint is disabled)
        if (killSwitchChecker.isActive(agent.id())) {
            return PreCheckResult.problem(
                    ProblemDetailFactory.build(ProblemCode.ENDPOINT_DISABLED,
                            "Agent temporarily unavailable",
                            "This agent is temporarily disabled. Please try again later.",
                            httpRequest.getRequestURI()),
                    503);
        }

        // 3. Principal
        DaiPrincipal principal;
        Authentication auth;
        try {
            principal = principalResolver.resolve(httpRequest);
            auth = SecurityContextHolder.getContext().getAuthentication();
        } catch (Exception e) {
            return PreCheckResult.problem(
                    ProblemDetailFactory.build(ProblemCode.ACCESS_DENIED,
                            "Authentication required", null, httpRequest.getRequestURI()),
                    403);
        }
        if (auth == null) {
            return PreCheckResult.problem(
                    ProblemDetailFactory.build(ProblemCode.ACCESS_DENIED,
                            "Authentication required", null, httpRequest.getRequestURI()),
                    403);
        }

        // 4. Authorization
        var resource = ResourceRef.of(agent.workspaceId(), agent.id(),
                com.springaimcpservercommon.annotations.Classification.PUBLIC);
        var authReq = AuthorizationRequest.onResource(principal,
                com.springaimcpservercommon.security.permission.Permission.ENDPOINT_INVOKE, resource);
        var outcome = authorizationEngine.decide(authReq);
        if (outcome instanceof AuthorizationOutcome.Deny) {
            return PreCheckResult.problem(
                    ProblemDetailFactory.build(ProblemCode.ACCESS_DENIED,
                            "Access denied", null, httpRequest.getRequestURI()),
                    403);
        }

        // 5. Rate limit
        int retryAfter = rateLimiter.checkAndRecord(principal, agent.id());
        if (retryAfter >= 0) {
            return PreCheckResult.problem(
                    ProblemDetailFactory.buildRateLimited(httpRequest.getRequestURI(), retryAfter),
                    429);
        }

        // 6. Validate body
        if (body.message() == null || body.message().isBlank()) {
            return PreCheckResult.problem(
                    ProblemDetailFactory.buildValidation(httpRequest.getRequestURI(),
                            java.util.List.of(new ProblemDetailFactory.FieldViolation("message", "must not be blank"))),
                    400);
        }

        return PreCheckResult.ok(agent, principal, auth);
    }

    /** Internal result holder for pre-checks. */
    private record PreCheckResult(
            @Nullable AgentDefinition agent,
            @Nullable DaiPrincipal principal,
            @Nullable Authentication authentication,
            @Nullable String problem,
            int problemStatus) {

        static PreCheckResult ok(AgentDefinition agent, DaiPrincipal principal, Authentication auth) {
            return new PreCheckResult(agent, principal, auth, null, 0);
        }

        static PreCheckResult problem(String json, int status) {
            return new PreCheckResult(null, null, null, json, status);
        }

        AgentDefinition agent() {
            return Objects.requireNonNull(agent, "agent");
        }

        DaiPrincipal principal() {
            return Objects.requireNonNull(principal, "principal");
        }

        Authentication authentication() {
            return Objects.requireNonNull(authentication, "authentication");
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Parses the {@code seq} from a {@code Last-Event-ID} header value of the form
     * {@code {turnId}:{seq}}. Returns {@code -1} (replay all) when the header is absent,
     * blank, or does not match the expected format for the given turnId.
     */
    private static int parseAfterSeq(@Nullable String lastEventId, UUID turnId) {
        if (lastEventId == null || lastEventId.isBlank()) return -1;
        String prefix = turnId + ":";
        if (!lastEventId.startsWith(prefix)) return -1;
        try {
            return Integer.parseInt(lastEventId.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static ServerSentEvent<String> toSse(StreamEvent event, UUID turnId, int seq) {
        return ServerSentEvent.<String>builder()
                .id(turnId + ":" + seq)
                .event(event.type())
                .data(event.toJson())
                .build();
    }

    private static ServerSentEvent<String> errorEvent(UUID turnId, String code, String title,
                                                       boolean retryable, int seq) {
        var event = new StreamEvent.ErrorEvent(
                "https://dynamic-ai/problems/" + code, title, code, retryable, turnId);
        return ServerSentEvent.<String>builder()
                .id(turnId + ":" + seq)
                .event("error")
                .data(event.toJson())
                .build();
    }

    private static String syncResultToJson(AgentInvoker.SyncChatResult result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("conversationId", result.conversationId().toString());
        m.put("turnId", result.turnId().toString());
        m.put("message", result.message());

        var toolCallMaps = result.toolCalls().stream()
                .map(tc -> {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("callId", tc.callId());
                    t.put("tool", tc.tool());
                    t.put("status", tc.status());
                    return t;
                })
                .toList();
        m.put("toolCalls", toolCallMaps);

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("inputTokens", result.usage().inputTokens());
        usage.put("outputTokens", result.usage().outputTokens());
        m.put("usage", usage);

        return CanonicalJson.write(m);
    }

    private static ProblemCode mapAgentCode(String code) {
        return switch (code) {
            case "agent-disabled" -> ProblemCode.ENDPOINT_DISABLED;
            case "budget-exhausted" -> ProblemCode.BUDGET_EXHAUSTED;
            case "model-unavailable" -> ProblemCode.EXECUTION_ERROR;
            case "turn-timeout" -> ProblemCode.EXECUTION_TIMEOUT;
            default -> ProblemCode.INTERNAL_ERROR;
        };
    }
}
