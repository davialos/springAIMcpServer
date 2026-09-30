package com.springaimcpservercommon.mcp.server;

import com.springaimcpservercommon.core.id.Ids;
import com.springaimcpservercommon.core.json.CanonicalJson;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.ai.tool.ToolCallScope;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.security.core.Authentication;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The MCP wire protocol (spec 2025-11-25, Streamable HTTP) for one stateless POST: JSON-RPC 2.0 parsing, the
 * {@code initialize}, {@code ping}, {@code tools/list} and {@code tools/call} methods, and recording of every request
 * (LLD-07 §5, ADR-0016). It knows nothing about servlets, authentication or the store, so it is unit-testable.
 *
 * <p>Stateless: no session, no server-to-client messages, the tool list is computed for the caller on every request
 * (so a grant change is effective immediately) and tools run through the secured callbacks the {@link ToolLister}
 * returns — permission re-check, argument constraints, read-only scope, proposal instead of write (LLD-11).
 * A tool the caller may not use is indistinguishable from an unknown tool (no existence oracle).
 *
 * <p>Batches, resources, prompts, sampling and logging are not supported: they answer {@code -32600} /
 * {@code -32601}. Never logs arguments or results.
 */
@NullMarked
public final class McpProtocolHandler {

    /** Protocol versions this server speaks, newest first. */
    public static final List<String> SUPPORTED_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26");

    /** JSON-RPC: invalid JSON. */
    static final int PARSE_ERROR = -32700;
    /** JSON-RPC: not a valid request object. */
    static final int INVALID_REQUEST = -32600;
    /** JSON-RPC: unknown method. */
    static final int METHOD_NOT_FOUND = -32601;
    /** JSON-RPC: invalid parameters (also unknown tool, per the MCP spec). */
    static final int INVALID_PARAMS = -32602;
    /** JSON-RPC: internal error. */
    static final int INTERNAL_ERROR = -32603;

    private static final Logger LOG = LoggerFactory.getLogger(McpProtocolHandler.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Pattern TOOL_NAME = Pattern.compile("^[a-z][a-z0-9_]{2,63}$");
    private static final Pattern METHOD_NAME = Pattern.compile("^[a-z][A-Za-z/_]{1,63}$");
    private static final int MAX_ID_CHARS = 128;

    /**
     * Who is calling and for which workspace; established by the HTTP layer before the handler runs.
     *
     * @param principal      the authenticated caller
     * @param authentication the caller's Spring Security authentication (tools run as this identity)
     * @param workspaceId    workspace the request addresses
     * @param mcpClientId    approved MCP client, if known
     */
    public record Caller(DaiPrincipal principal, Authentication authentication, UUID workspaceId,
                         @Nullable UUID mcpClientId) {
        /** Validates required components. */
        public Caller {
            Objects.requireNonNull(principal, "principal");
            Objects.requireNonNull(authentication, "authentication");
            Objects.requireNonNull(workspaceId, "workspaceId");
        }
    }

    /**
     * Port: the secured tool callbacks the caller may use for this request. The callbacks record their calls under
     * the given scope.
     */
    @FunctionalInterface
    public interface ToolLister {
        /**
         * @param caller the caller
         * @param scope  scope to build the callbacks with (channel MCP, this request's id)
         * @return callbacks the caller may see and call
         */
        List<ToolCallback> tools(Caller caller, ToolCallScope scope);
    }

    /**
     * Result of handling one POST body.
     *
     * @param httpStatus 200 with a JSON-RPC response, 202 for a notification, 400 for an unusable request
     * @param body       response body, {@code null} when there is none (202)
     */
    public record Reply(int httpStatus, @Nullable String body) {}

    private final ToolLister tools;
    private final McpRequestRecorder recorder;
    private final Clock clock;
    private final String serverName;
    private final String serverVersion;
    private final ObservationRegistry observations;

    /**
     * Creates the handler.
     *
     * @param tools         source of the caller's tools
     * @param recorder      receives one record per handled request
     * @param clock         time source
     * @param serverName    name reported in {@code serverInfo}
     * @param serverVersion version reported in {@code serverInfo}
     */
    public McpProtocolHandler(ToolLister tools, McpRequestRecorder recorder, Clock clock, String serverName,
                              String serverVersion) {
        this(tools, recorder, clock, serverName, serverVersion, ObservationRegistry.NOOP);
    }

    /**
     * Creates the handler with tracing.
     *
     * @param tools         source of the caller's tools
     * @param recorder      receives one record per handled request
     * @param clock         time source
     * @param serverName    name reported in {@code serverInfo}
     * @param serverVersion version reported in {@code serverInfo}
     * @param observations  registry for the {@code dai.mcp} span (one per request)
     */
    public McpProtocolHandler(ToolLister tools, McpRequestRecorder recorder, Clock clock, String serverName,
                              String serverVersion, ObservationRegistry observations) {
        this.tools = Objects.requireNonNull(tools, "tools");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.serverName = Objects.requireNonNull(serverName, "serverName");
        this.serverVersion = Objects.requireNonNull(serverVersion, "serverVersion");
        this.observations = Objects.requireNonNull(observations, "observations");
    }

    /**
     * Handles one POST body.
     *
     * @param body   the request body (one JSON-RPC message)
     * @param caller the caller
     * @return the reply to send
     */
    public Reply handle(String body, Caller caller) {
        Observation observation = Observation.createNotStarted("dynamic.ai.agent.mcp", observations)
                .contextualName("dai.mcp")
                .highCardinalityKeyValue("dai.workspace.id", caller.workspaceId().toString())
                .start();
        // open while the request is handled: the trace id recorded with the request, the tool spans and the
        // spans of what the tools call all belong to this span
        try (Observation.Scope ignored = observation.openScope()) {
            return handleMessage(body, caller);
        } catch (RuntimeException e) {
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }

    private Reply handleMessage(String body, Caller caller) {
        Instant received = clock.instant();
        UUID requestId = Ids.newId();
        Object root;
        try {
            root = MAPPER.readerFor(Object.class).readValue(body);
        } catch (RuntimeException e) {
            return finish(requestId, received, caller, "unknown", null, null, McpRequestRecorder.Status.ERROR,
                    "parse_error", error(null, PARSE_ERROR, "Parse error"), 400);
        }
        if (!(root instanceof Map<?, ?> message)) {
            return finish(requestId, received, caller, "unknown", null, null, McpRequestRecorder.Status.ERROR,
                    "invalid_request", error(null, INVALID_REQUEST, "Batches are not supported"), 400);
        }
        Object id = message.get("id");
        boolean hasId = message.containsKey("id");
        if (hasId && !(id instanceof String || id instanceof Integer || id instanceof Long)) {
            return finish(requestId, received, caller, "unknown", null, null, McpRequestRecorder.Status.ERROR,
                    "invalid_request", error(null, INVALID_REQUEST, "Invalid id"), 400);
        }
        String idText = hasId ? truncate(String.valueOf(id)) : null;
        Object methodValue = message.get("method");
        if (!"2.0".equals(message.get("jsonrpc")) || !(methodValue instanceof String method)) {
            // a JSON-RPC response from the client (no method) or a malformed message: nothing to answer
            if (hasId && !message.containsKey("method")) {
                return finish(requestId, received, caller, "unknown", idText, null, McpRequestRecorder.Status.OK, null,
                        null, 202);
            }
            return finish(requestId, received, caller, "unknown", idText, null, McpRequestRecorder.Status.ERROR,
                    "invalid_request", error(hasId ? id : null, INVALID_REQUEST, "Invalid request"), 400);
        }
        String recordedMethod = METHOD_NAME.matcher(method).matches() ? method : "unknown";
        if (!hasId) {
            // notification: notifications/initialized, notifications/cancelled, ...
            return finish(requestId, received, caller, recordedMethod, null, null, McpRequestRecorder.Status.OK, null,
                    null, 202);
        }
        Object params = message.get("params");
        return switch (method) {
            case "initialize" -> initialize(requestId, received, caller, id, idText, params);
            case "ping" -> finish(requestId, received, caller, "ping", idText, null, McpRequestRecorder.Status.OK,
                    null, result(id, Map.of()), 200);
            case "tools/list" -> toolsList(requestId, received, caller, id, idText);
            case "tools/call" -> toolsCall(requestId, received, caller, id, idText, params);
            default -> finish(requestId, received, caller, recordedMethod, idText, null,
                    McpRequestRecorder.Status.ERROR, "method_not_found",
                    error(id, METHOD_NOT_FOUND, "Method not found"), 200);
        };
    }

    // ── methods ────────────────────────────────────────────────────────────────────────────────────────────────

    private Reply initialize(UUID requestId, Instant received, Caller caller, Object id, @Nullable String idText,
                             @Nullable Object params) {
        String requested = params instanceof Map<?, ?> p && p.get("protocolVersion") instanceof String v ? v : null;
        String version = requested != null && SUPPORTED_VERSIONS.contains(requested) ? requested
                : SUPPORTED_VERSIONS.getFirst();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", version);
        result.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        result.put("serverInfo", Map.of("name", serverName, "version", serverVersion));
        return finish(requestId, received, caller, "initialize", idText, null, McpRequestRecorder.Status.OK, null,
                result(id, result), 200);
    }

    private Reply toolsList(UUID requestId, Instant received, Caller caller, Object id, @Nullable String idText) {
        try {
            List<Object> listed = tools.tools(caller, ToolCallScope.ofMcpRequest(requestId)).stream()
                    .sorted(Comparator.comparing(t -> t.getToolDefinition().name()))
                    .<Object>map(McpProtocolHandler::describe)
                    .toList();
            return finish(requestId, received, caller, "tools/list", idText, null, McpRequestRecorder.Status.OK, null,
                    result(id, Map.of("tools", listed)), 200);
        } catch (RuntimeException e) {
            return internal(requestId, received, caller, "tools/list", id, idText, null, e);
        }
    }

    private Reply toolsCall(UUID requestId, Instant received, Caller caller, Object id, @Nullable String idText,
                            @Nullable Object params) {
        if (!(params instanceof Map<?, ?> p) || !(p.get("name") instanceof String name)) {
            return finish(requestId, received, caller, "tools/call", idText, null, McpRequestRecorder.Status.ERROR,
                    "invalid_params", error(id, INVALID_PARAMS, "Missing tool name"), 200);
        }
        String recordedTool = TOOL_NAME.matcher(name).matches() ? name : null;
        Object arguments = p.get("arguments");
        if (arguments != null && !(arguments instanceof Map<?, ?>)) {
            return finish(requestId, received, caller, "tools/call", idText, recordedTool,
                    McpRequestRecorder.Status.ERROR, "invalid_params",
                    error(id, INVALID_PARAMS, "arguments must be an object"), 200);
        }
        try {
            ToolCallback tool = tools.tools(caller, ToolCallScope.ofMcpRequest(requestId)).stream()
                    .filter(t -> t.getToolDefinition().name().equals(name))
                    .findFirst().orElse(null);
            if (tool == null) {
                return finish(requestId, received, caller, "tools/call", idText, recordedTool,
                        McpRequestRecorder.Status.ERROR, "unknown_tool",
                        error(id, INVALID_PARAMS, "Unknown tool"), 200);
            }
            String input = CanonicalJson.write(arguments == null ? Map.of() : arguments);
            String output = tool.call(input);
            String status = envelopeStatus(output);
            boolean isError = "error".equals(status) || "not_permitted".equals(status)
                    || "unavailable".equals(status);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", List.of(Map.of("type", "text", "text", output == null ? "" : output)));
            result.put("isError", isError);
            return finish(requestId, received, caller, "tools/call", idText, recordedTool,
                    "not_permitted".equals(status) ? McpRequestRecorder.Status.DENIED : McpRequestRecorder.Status.OK,
                    null, result(id, result), 200);
        } catch (IllegalArgumentException e) {
            return finish(requestId, received, caller, "tools/call", idText, recordedTool,
                    McpRequestRecorder.Status.ERROR, "invalid_params",
                    error(id, INVALID_PARAMS, "Invalid arguments"), 200);
        } catch (RuntimeException e) {
            return internal(requestId, received, caller, "tools/call", id, idText, recordedTool, e);
        }
    }

    private Reply internal(UUID requestId, Instant received, Caller caller, String method, Object id,
                           @Nullable String idText, @Nullable String tool, RuntimeException e) {
        LOG.error("MCP {} failed for principal {} ({})", method, caller.principal().principalId(),
                e.getClass().getSimpleName());
        return finish(requestId, received, caller, method, idText, tool, McpRequestRecorder.Status.ERROR,
                "internal_error", error(id, INTERNAL_ERROR, "Internal error"), 200);
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> describe(ToolCallback tool) {
        ToolDefinition def = tool.getToolDefinition();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", def.name());
        entry.put("description", def.description());
        Object schema;
        try {
            schema = MAPPER.readerFor(Object.class).readValue(def.inputSchema());
        } catch (RuntimeException e) {
            schema = Map.of("type", "object");
        }
        entry.put("inputSchema", schema instanceof Map<?, ?> ? schema : Map.of("type", "object"));
        return entry;
    }

    /** The {@code status} of a tool result envelope, or {@code null} when the output is not an envelope. */
    static @Nullable String envelopeStatus(@Nullable String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        try {
            Object parsed = MAPPER.readerFor(Object.class).readValue(output);
            return parsed instanceof Map<?, ?> m && m.get("status") instanceof String s ? s : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String result(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return CanonicalJson.write(response);
    }

    private static String error(@Nullable Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));
        return CanonicalJson.write(response);
    }

    /**
     * Adds the outcome to the current {@code dai.mcp} span. The method is a tag, so it is limited to the known
     * methods (anything else is {@code other}); tool name and request id are span attributes only, never tags, and
     * never carry arguments or results.
     */
    private void annotate(UUID requestId, String method, @Nullable String tool, McpRequestRecorder.Status status,
                          @Nullable String errorCode) {
        Observation current = observations.getCurrentObservation();
        if (current == null) {
            return;
        }
        current.lowCardinalityKeyValue("dai.mcp.method", spanMethod(method));
        current.lowCardinalityKeyValue("dai.mcp.status", status.name());
        if (errorCode != null) {
            current.lowCardinalityKeyValue("dai.mcp.error_code", errorCode);
        }
        current.highCardinalityKeyValue("dai.mcp.request.id", requestId.toString());
        if (tool != null) {
            current.highCardinalityKeyValue("dai.mcp.tool", tool);
        }
    }

    private static String spanMethod(String method) {
        return switch (method) {
            case "initialize", "ping", "tools/list", "tools/call" -> method;
            default -> method.startsWith("notifications/") ? "notification" : "other";
        };
    }

    private static String truncate(String text) {
        return text.length() > MAX_ID_CHARS ? text.substring(0, MAX_ID_CHARS) : text;
    }

    private Reply finish(UUID requestId, Instant received, Caller caller, String method, @Nullable String idText,
                         @Nullable String tool, McpRequestRecorder.Status status, @Nullable String errorCode,
                         @Nullable String body, int httpStatus) {
        annotate(requestId, method, tool, status, errorCode);
        try {
            recorder.record(new McpRequestRecorder.McpCall(requestId, received, clock.instant(),
                    caller.principal().principalId(), caller.workspaceId(), caller.mcpClientId(), method, idText, tool,
                    status, errorCode, MDC.get("traceId")));
        } catch (RuntimeException e) {
            LOG.warn("Recording of MCP request failed ({}); the request is unaffected", e.getClass().getSimpleName());
        }
        return new Reply(httpStatus, body);
    }
}
