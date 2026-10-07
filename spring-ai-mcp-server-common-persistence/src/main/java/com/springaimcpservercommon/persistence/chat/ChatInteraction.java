package com.springaimcpservercommon.persistence.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * A component shown in a chat and the user's answer ({@code dai_chat_interaction}). Read-only through JPA: rows are
 * inserted and answered with native statements that are race-safe ({@link ChatUiStore}).
 */
@Entity
@Immutable
@Table(name = "dai_chat_interaction")
public class ChatInteraction {

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

    @Column(name = "component_id", nullable = false, updatable = false)
    private String componentId;

    @Column(name = "component_type", nullable = false, updatable = false)
    private String componentType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answer", updatable = false)
    private @Nullable String answer;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "answered_at", updatable = false)
    private @Nullable Instant answeredAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /** For JPA only. */
    protected ChatInteraction() {
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

    /** @return the user the component was shown to */
    public UUID getPrincipalId() {
        return principalId;
    }

    /** @return turn that showed it */
    public UUID getTurnId() {
        return turnId;
    }

    /** @return id within the turn */
    public String getComponentId() {
        return componentId;
    }

    /** @return component type */
    public String getComponentType() {
        return componentType;
    }

    /** @return payload JSON as shown */
    public String getPayload() {
        return payload;
    }

    /** @return answer JSON, or {@code null} while unanswered */
    public @Nullable String getAnswer() {
        return answer;
    }

    /** @return when it was shown */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** @return when it was answered, or {@code null} */
    public @Nullable Instant getAnsweredAt() {
        return answeredAt;
    }

    /** @return when it expires */
    public Instant getExpiresAt() {
        return expiresAt;
    }
}
