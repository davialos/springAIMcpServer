package com.springaimcpservercommon.persistence.telemetry;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * A message to append to a conversation. The content must already be redacted (tool results masked) — LLD-06 §7.
 *
 * @param role       role
 * @param content    redacted content
 * @param redacted   whether redaction changed the content
 * @param turnId     agent turn that produced or consumed it, if any
 * @param toolCallId provider tool call id; required for TOOL messages
 * @param tokenCount token count, if known
 */
public record NewMessage(
        MessageRole role,
        String content,
        boolean redacted,
        @Nullable UUID turnId,
        @Nullable String toolCallId,
        @Nullable Integer tokenCount) {
}
