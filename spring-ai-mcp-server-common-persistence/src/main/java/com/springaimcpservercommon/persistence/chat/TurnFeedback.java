package com.springaimcpservercommon.persistence.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Like/dislike on one agent answer ({@code dai_turn_feedback}). Read-only through JPA: rows are upserted and deleted
 * with native statements ({@link ChatUiStore}).
 */
@Entity
@Immutable
@Table(name = "dai_turn_feedback")
public class TurnFeedback {

    /** Rating values ({@code ck_turn_feedback_rating}). */
    public enum Rating {
        /** Helpful. */
        UP,
        /** Not helpful. */
        DOWN
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "conversation_key", nullable = false, updatable = false)
    private String conversationKey;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "agent_id", nullable = false, updatable = false)
    private UUID agentId;

    @Column(name = "principal_id", nullable = false, updatable = false)
    private UUID principalId;

    @Column(name = "turn_id", nullable = false, updatable = false)
    private UUID turnId;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "rating", nullable = false, updatable = false)
    private Rating rating;

    @Column(name = "reason", updatable = false)
    private @Nullable String reason;

    @Column(name = "comment", updatable = false)
    private @Nullable String comment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /** For JPA only. */
    protected TurnFeedback() {
    }

    /** @return row id */
    public UUID getId() {
        return id;
    }

    /** @return conversation key hash */
    public String getConversationKey() {
        return conversationKey;
    }

    /** @return agent workspace */
    public UUID getWorkspaceId() {
        return workspaceId;
    }

    /** @return agent resource id */
    public UUID getAgentId() {
        return agentId;
    }

    /** @return the user who gave it */
    public UUID getPrincipalId() {
        return principalId;
    }

    /** @return the answer's turn */
    public UUID getTurnId() {
        return turnId;
    }

    /** @return the rating */
    public Rating getRating() {
        return rating;
    }

    /** @return reason code, or {@code null} */
    public @Nullable String getReason() {
        return reason;
    }

    /** @return comment (PII-redacted), or {@code null} */
    public @Nullable String getComment() {
        return comment;
    }

    /** @return first given */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** @return last changed */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** @return when it expires */
    public Instant getExpiresAt() {
        return expiresAt;
    }
}
