package com.springaimcpservercommon.persistence.telemetry;

import com.springaimcpservercommon.core.id.Ids;
import com.springaimcpservercommon.persistence.support.Checks;
import com.springaimcpservercommon.persistence.support.UtcTimes;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * One message of a conversation ({@code dai_conversation_message}); {@code seq} is contiguous per conversation and
 * assigned by the store under the conversation's row lock. Immutable; removed by erasure or retention.
 */
@Entity
@Immutable
@Table(name = "dai_conversation_message")
public class ConversationMessage {

    /** Largest content accepted (characters). */
    public static final int MAX_CONTENT_LENGTH = 1_000_000;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(name = "seq", nullable = false, updatable = false)
    private int seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, updatable = false)
    private MessageRole role;

    @Column(name = "content", nullable = false, updatable = false)
    private String content;

    @Column(name = "redacted", nullable = false, updatable = false)
    private boolean redacted;

    @Column(name = "turn_id", updatable = false)
    private @Nullable UUID turnId;

    @Column(name = "tool_call_id", updatable = false)
    private @Nullable String toolCallId;

    @Column(name = "token_count", updatable = false)
    private @Nullable Integer tokenCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** For JPA only. */
    protected ConversationMessage() {
    }

    /**
     * Creates a message row after validating the {@code ck_conversation_message_*} rules.
     *
     * @param conversationId conversation
     * @param seq            position (≥ 0)
     * @param message        message data
     * @param now            current time
     * @return a new, unsaved entity
     */
    public static ConversationMessage of(UUID conversationId, int seq, NewMessage message, Instant now) {
        ConversationMessage m = new ConversationMessage();
        m.id = Ids.newId();
        m.conversationId = Checks.required(conversationId, "conversationId");
        m.seq = (int) Checks.nonNegative(seq, "seq");
        m.role = Checks.required(message.role(), "role");
        String content = Checks.required(message.content(), "content");
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("content must be at most " + MAX_CONTENT_LENGTH + " characters");
        }
        m.content = content;
        m.redacted = message.redacted();
        m.turnId = message.turnId();
        m.toolCallId = Checks.optionalText(message.toolCallId(), "toolCallId", 256);
        if (m.role == MessageRole.TOOL && m.toolCallId == null) {
            throw new IllegalArgumentException("toolCallId is required for TOOL messages");
        }
        Integer tokens = message.tokenCount();
        if (tokens != null) {
            Checks.nonNegative(tokens, "tokenCount");
        }
        m.tokenCount = tokens;
        m.createdAt = UtcTimes.micros(now);
        return m;
    }

    /** @return message id */
    public UUID getId() {
        return id;
    }

    /** @return conversation id */
    public UUID getConversationId() {
        return conversationId;
    }

    /** @return position in the conversation */
    public int getSeq() {
        return seq;
    }

    /** @return role */
    public MessageRole getRole() {
        return role;
    }

    /** @return redacted content */
    public String getContent() {
        return content;
    }

    /** @return whether redaction changed the content */
    public boolean isRedacted() {
        return redacted;
    }

    /** @return agent turn, if any */
    public @Nullable UUID getTurnId() {
        return turnId;
    }

    /** @return tool call id, if any */
    public @Nullable String getToolCallId() {
        return toolCallId;
    }

    /** @return token count, if known */
    public @Nullable Integer getTokenCount() {
        return tokenCount;
    }

    /** @return creation time */
    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof ConversationMessage other && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "ConversationMessage[" + id + ", seq " + seq + "]";
    }
}
