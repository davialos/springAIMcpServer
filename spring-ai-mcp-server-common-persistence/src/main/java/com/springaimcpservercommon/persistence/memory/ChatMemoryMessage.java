package com.springaimcpservercommon.persistence.memory;

import com.springaimcpservercommon.core.id.Ids;
import com.springaimcpservercommon.persistence.support.Checks;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * One message of a conversation's model memory ({@code dai_chat_memory_message}). Immutable; a memory is replaced as
 * a whole and removed by expiry or explicit delete.
 */
@Entity
@Immutable
@Table(name = "dai_chat_memory_message")
public class ChatMemoryMessage {

    /** Largest content accepted (characters). */
    public static final int MAX_CONTENT_LENGTH = 1_000_000;

    /** Roles a memory can hold (tool traffic is not memory: it lives inside a turn's tool-calling loop). */
    public enum Role {
        /** What the user said. */
        USER,
        /** What the model answered. */
        ASSISTANT,
        /** A system message. */
        SYSTEM
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "memory_key", nullable = false, updatable = false)
    private String memoryKey;

    @Column(name = "seq", nullable = false, updatable = false)
    private int seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, updatable = false)
    private Role role;

    @Column(name = "content", nullable = false, updatable = false)
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /** For JPA only. */
    protected ChatMemoryMessage() {
    }

    /**
     * Creates a row after validating the {@code ck_chat_memory_message_*} rules.
     *
     * @param memoryKey {@code sha256:<hex>} of the conversation key
     * @param seq       position (≥ 0)
     * @param entry     role and content
     * @param now       current time
     * @param expiresAt when the memory expires
     * @return a new, unsaved entity
     */
    public static ChatMemoryMessage of(String memoryKey, int seq, ChatMemoryStore.Entry entry, Instant now,
                                       Instant expiresAt) {
        ChatMemoryMessage m = new ChatMemoryMessage();
        m.id = Ids.newId();
        m.memoryKey = Checks.sha256(memoryKey, "memoryKey");
        m.seq = (int) Checks.nonNegative(seq, "seq");
        m.role = Checks.required(entry.role(), "role");
        String content = Checks.required(entry.content(), "content");
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("content must be at most " + MAX_CONTENT_LENGTH + " characters");
        }
        m.content = content;
        m.createdAt = Checks.required(now, "now");
        m.expiresAt = Checks.required(expiresAt, "expiresAt");
        Checks.notBefore(now, expiresAt, "expiresAt");
        return m;
    }

    /** @return the role */
    public Role getRole() {
        return role;
    }

    /** @return the (redacted) content */
    public String getContent() {
        return content;
    }

    /** @return position within the memory */
    public int getSeq() {
        return seq;
    }

    /** @return when the memory expires */
    public Instant getExpiresAt() {
        return expiresAt;
    }
}
