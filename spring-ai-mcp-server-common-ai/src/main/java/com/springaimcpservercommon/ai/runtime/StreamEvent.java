package com.springaimcpservercommon.ai.runtime;

import com.springaimcpservercommon.core.json.CanonicalJson;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sealed hierarchy of typed SSE stream events (LLD-13 §3, protocol {@code dai-stream/1}).
 *
 * <p>Every event instance serialises to the JSON that goes in the SSE {@code data:} field.
 * The SSE {@code event:} name is returned by {@link #type()} and matches the JSON {@code type} field.
 * The SSE {@code id:} field is set by the streaming controller using {@code turnId:seq}.
 */
@NullMarked
public sealed interface StreamEvent
        permits StreamEvent.TurnStart,
                StreamEvent.TextDelta,
                StreamEvent.ToolCall,
                StreamEvent.ToolResult,
                StreamEvent.UiComponent,
                StreamEvent.ProposalCreated,
                StreamEvent.ProposalUpdated,
                StreamEvent.ProposalApplied,
                StreamEvent.UsageEvent,
                StreamEvent.TurnEnd,
                StreamEvent.ErrorEvent {

    /** SSE event type name, also present as the {@code type} field in the JSON payload. */
    String type();

    /** Serialises this event to the JSON string used in the SSE {@code data:} field. */
    String toJson();

    // ─── Event types ───────────────────────────────────────────────────────────

    /**
     * First event in every stream. Carries stream metadata.
     *
     * @param turnId         unique turn id (UUIDv7)
     * @param conversationId conversation this turn belongs to
     * @param agent          agent slug
     * @param revision       agent revision being executed
     * @param protocol       always {@link #PROTOCOL}
     */
    record TurnStart(UUID turnId, UUID conversationId, String agent, int revision, String protocol)
            implements StreamEvent {

        /** Current stream protocol version. */
        public static final String PROTOCOL = "dai-stream/1";

        @Override
        public String type() { return "turn.start"; }

        @Override
        public String toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type());
            m.put("turnId", turnId.toString());
            m.put("conversationId", conversationId.toString());
            m.put("agent", agent);
            m.put("revision", revision);
            m.put("protocol", protocol);
            return CanonicalJson.write(m);
        }
    }

    /**
     * A token-by-token text chunk from the model (released after the server-side guardrail window).
     *
     * @param seq  monotonically increasing sequence number within the turn
     * @param text token text fragment
     */
    record TextDelta(int seq, String text) implements StreamEvent {

        @Override
        public String type() { return "text.delta"; }

        @Override
        public String toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type());
            m.put("seq", seq);
            m.put("text", text);
            return CanonicalJson.write(m);
        }
    }

    /**
     * The model requested a tool call. {@code argsPreview} is a redacted summary.
     *
     * @param callId      unique call id for correlation with {@link ToolResult}
     * @param tool        tool name
     * @param argsPreview redacted, non-sensitive summary of the arguments
     */
    record ToolCall(String callId, String tool, String argsPreview) implements StreamEvent {

        @Override
        public String type() { return "tool.call"; }

        @Override
        public String toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type());
            m.put("callId", callId);
            m.put("tool", tool);
            m.put("argsPreview", argsPreview);
            return CanonicalJson.write(m);
        }
    }

    /**
     * A tool call completed. The full payload is not streamed — only the status and a safe summary.
     *
     * @param callId  correlates with {@link ToolCall}
     * @param status  one of {@code ok|empty|truncated|error|not_permitted|unavailable|proposed}
     * @param summary short human-readable summary safe to display
     */
    record ToolResult(String callId, String status, String summary) implements StreamEvent {

        @Override
        public String type() { return "tool.result"; }

        @Override
        public String toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type());
            m.put("callId", callId);
            m.put("status", status);
            m.put("summary", summary);
            return CanonicalJson.write(m);
        }
    }

    /**
     * A UI component payload (LLD-11 §7). Always a complete JSON object, never split.
     *
     * @param componentType component type identifier
     * @param payload       complete component JSON payload
     */
    record UiComponent(String componentType, String payload) implements StreamEvent {

        @Override
        public String type() { return "ui.component"; }

        @Override
        public String toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type());
            m.put("componentType", componentType);
            m.put("payload", payload);
            return CanonicalJson.write(m);
        }
    }

    /**
     * A write proposal was created and is awaiting review.
     *
     * @param proposalId UUID of the created proposal
     * @param summary    short description of the proposed change
     * @param reviewUrl  URL the user must visit to review/confirm
     */
    record ProposalCreated(UUID proposalId, String summary, String reviewUrl) implements StreamEvent {

        @Override
        public String type() { return "proposal.created"; }

        @Override
        public String toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type());
            m.put("proposalId", proposalId.toString());
            m.put("summary", summary);
            m.put("reviewUrl", reviewUrl);
            return CanonicalJson.write(m);
        }
    }

    /**
     * A proposal's state changed (e.g. from {@code PENDING} to {@code CONFIRMED}).
     *
     * @param proposalId UUID of the proposal
     * @param state      new state
     */
    record ProposalUpdated(UUID proposalId, String state) implements StreamEvent {

        @Override
        public String type() { return "proposal.updated"; }

        @Override
        public String toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type());
            m.put("proposalId", proposalId.toString());
            m.put("state", state);
            return CanonicalJson.write(m);
        }
    }

    /**
     * A proposal was successfully applied to the data store.
     *
     * @param proposalId UUID of the applied proposal
     */
    record ProposalApplied(UUID proposalId) implements StreamEvent {

        @Override
        public String type() { return "proposal.applied"; }

        @Override
        public String toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type());
            m.put("proposalId", proposalId.toString());
            return CanonicalJson.write(m);
        }
    }

    /**
     * Token usage for this turn. Sent immediately before {@link TurnEnd}.
     *
     * @param inputTokens  prompt tokens consumed
     * @param outputTokens completion tokens produced
     * @param costMicros   estimated cost in micros of the workspace's billing currency (0 if unknown)
     * @param model        model identifier that served this turn
     */
    record UsageEvent(long inputTokens, long outputTokens, long costMicros, String model)
            implements StreamEvent {

        @Override
        public String type() { return "usage"; }

        @Override
        public String toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type());
            m.put("inputTokens", inputTokens);
            m.put("outputTokens", outputTokens);
            m.put("costMicros", costMicros);
            m.put("model", model);
            return CanonicalJson.write(m);
        }
    }

    /**
     * Last event of a successful turn.
     *
     * @param finishReason  one of {@code stop|length|tool_limit|budget|cancelled}
     * @param messageId     persisted message id for history retrieval
     */
    record TurnEnd(String finishReason, @Nullable UUID messageId) implements StreamEvent {

        @Override
        public String type() { return "turn.end"; }

        @Override
        public String toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type());
            m.put("finishReason", finishReason);
            if (messageId != null) m.put("messageId", messageId.toString());
            return CanonicalJson.write(m);
        }
    }

    /**
     * Terminal failure event emitted after {@code 200 OK} has been sent and the stream must close.
     *
     * <p>Exception messages and stack traces are NEVER included — only the stable {@code code}
     * from the problem catalog is safe to expose to the client (LLD-13 §4).
     *
     * @param errorType RFC 9457 type URI
     * @param title     human-readable title
     * @param code      stable problem code (e.g. {@code model-timeout})
     * @param retryable whether the client may safely retry
     * @param turnId    turn id for correlation with server-side logs
     */
    record ErrorEvent(String errorType, String title, String code, boolean retryable, UUID turnId)
            implements StreamEvent {

        @Override
        public String type() { return "error"; }

        @Override
        public String toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type());
            m.put("errorType", errorType);
            m.put("title", title);
            m.put("code", code);
            m.put("retryable", retryable);
            m.put("turnId", turnId.toString());
            return CanonicalJson.write(m);
        }
    }
}
