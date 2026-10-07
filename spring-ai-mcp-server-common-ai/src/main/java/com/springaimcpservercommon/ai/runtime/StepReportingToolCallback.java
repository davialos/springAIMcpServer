package com.springaimcpservercommon.ai.runtime;

import com.springaimcpservercommon.core.display.AnswerContent;
import com.springaimcpservercommon.core.guard.PiiRedactor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reports a tool call to the turn's step details as {@code tool.call} and {@code tool.result} events (LLD-13 §3) and
 * otherwise delegates unchanged (the security wrapper, recording and result shaping stay in the delegate).
 *
 * <p>What is shown is safe by construction: the argument preview is PII-redacted and cut, and the result summary is
 * built only from the envelope's status, entity name, row count and its displayable error message, never from rows.
 */
final class StepReportingToolCallback implements ToolCallback {

    /** Longest argument preview. */
    static final int MAX_PREVIEW = 300;

    private final ToolCallback delegate;
    private final TurnEvents events;
    private final PiiRedactor redactor;
    private final AtomicInteger counter;

    StepReportingToolCallback(ToolCallback delegate, TurnEvents events, PiiRedactor redactor, AtomicInteger counter) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.events = events;
        this.redactor = redactor;
        this.counter = counter;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return report(toolInput, () -> delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return report(toolInput, () -> delegate.call(toolInput, toolContext));
    }

    private String report(String toolInput, java.util.function.Supplier<String> invocation) {
        String callId = "tool-" + counter.incrementAndGet();
        events.emit(new StreamEvent.ToolCall(callId, delegate.getToolDefinition().name(), preview(toolInput)));
        String result;
        try {
            result = invocation.get();
        } catch (RuntimeException e) {
            events.emit(new StreamEvent.ToolResult(callId, "error", "The tool failed."));
            throw e;
        }
        Map<?, ?> envelope = AnswerContent.parseJson(result) instanceof Map<?, ?> m ? m : Map.of();
        events.emit(new StreamEvent.ToolResult(callId, status(envelope), summary(envelope)));
        return result;
    }

    String preview(String toolInput) {
        String redacted = redactor.redact(toolInput).text().replaceAll("\\s+", " ").strip();
        return redacted.length() <= MAX_PREVIEW ? redacted : redacted.substring(0, MAX_PREVIEW) + "…";
    }

    static String status(Map<?, ?> envelope) {
        return envelope.get("status") instanceof String s ? s.toLowerCase(Locale.ROOT) : "ok";
    }

    static String summary(Map<?, ?> envelope) {
        String status = status(envelope);
        String entity = envelope.get("entity") instanceof String e ? e : null;
        long count = envelope.get("count") instanceof Number n ? n.longValue() : -1;
        boolean truncated = Boolean.TRUE.equals(envelope.get("truncated"));
        String summary = switch (status) {
            case "empty" -> "No matching " + (entity != null ? entity : "data") + " found.";
            case "error" -> envelope.get("errorMessage") instanceof String m && !m.isBlank()
                    ? cut(m) : "The tool reported an error.";
            case "not_permitted" -> "You are not permitted to use this tool.";
            case "unavailable" -> "The tool is currently unavailable.";
            case "proposed" -> "A change proposal was created for your review.";
            default -> count >= 0
                    ? count + " " + (entity != null ? entity : "item") + (count == 1 ? "" : "s") + " returned."
                    : "Done.";
        };
        return truncated ? summary + " (truncated)" : summary;
    }

    private static String cut(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
