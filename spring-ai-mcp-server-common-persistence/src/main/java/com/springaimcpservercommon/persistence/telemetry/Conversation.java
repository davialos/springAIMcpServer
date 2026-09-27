package com.springaimcpservercommon.persistence.telemetry;

import com.springaimcpservercommon.core.id.Ids;
import com.springaimcpservercommon.core.invocation.Channel;
import com.springaimcpservercommon.persistence.support.Checks;
import com.springaimcpservercommon.persistence.support.UtcTimes;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A chat conversation ({@code dai_conversation}) — the chat-memory container of LLD-06 §7. The retention span
 * ({@code retention_until - last_activity_at}) is fixed at creation and slides forward with every activity.
 */
@Entity
@Table(name = "dai_conversation")
public class Conversation {

    /** Longest retention span accepted. */
    public static final Duration MAX_RETENTION = Duration.ofDays(3660);

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "agent_resource_id", updatable = false)
    private @Nullable UUID agentResourceId;

    @Column(name = "principal_id", nullable = false, updatable = false)
    private UUID principalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false)
    private Channel channel;

    @Column(name = "conversation_key_hash", nullable = false, updatable = false)
    private String conversationKeyHash;

    @Column(name = "title")
    private @Nullable String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ConversationStatus status;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;

    @Column(name = "retention_until", nullable = false)
    private Instant retentionUntil;

    @Column(name = "erased_at")
    private @Nullable Instant erasedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    /** For JPA only. */
    protected Conversation() {
    }

    /**
     * Starts a conversation.
     *
     * @param conversation data
     * @param now          current time
     * @return a new, unsaved entity with a fresh UUIDv7
     */
    public static Conversation start(NewConversation conversation, Instant now) {
        Duration retention = Checks.required(conversation.retention(), "retention");
        if (retention.isNegative() || retention.isZero() || retention.compareTo(MAX_RETENTION) > 0) {
            throw new IllegalArgumentException("retention must be positive and at most " + MAX_RETENTION.toDays() + " days");
        }
        Conversation c = new Conversation();
        c.id = Ids.newId();
        c.conversationKeyHash = Checks.sha256(conversation.conversationKeyHash(), "conversationKeyHash");
        c.workspaceId = Checks.required(conversation.workspaceId(), "workspaceId");
        c.agentResourceId = conversation.agentResourceId();
        c.principalId = Checks.required(conversation.principalId(), "principalId");
        c.channel = Checks.required(conversation.channel(), "channel");
        c.title = Checks.optionalText(conversation.title(), "title", 500);
        c.status = ConversationStatus.ACTIVE;
        c.startedAt = UtcTimes.micros(now);
        c.lastActivityAt = c.startedAt;
        c.retentionUntil = UtcTimes.micros(c.startedAt.plus(retention));
        return c;
    }

    /**
     * Records activity: moves {@code last_activity_at} and slides {@code retention_until} by the same amount.
     *
     * @param now current time
     */
    public void touch(Instant now) {
        requireActive();
        Instant at = UtcTimes.micros(now);
        if (at.isAfter(lastActivityAt)) {
            Duration span = Duration.between(lastActivityAt, retentionUntil);
            lastActivityAt = at;
            retentionUntil = at.plus(span);
        }
    }

    /**
     * Closes the conversation; closed conversations accept no messages but are kept until retention.
     */
    public void close() {
        if (status == ConversationStatus.ACTIVE) {
            status = ConversationStatus.CLOSED;
        }
    }

    /**
     * Marks the conversation erased (messages are deleted by the store in the same transaction) and drops the title,
     * which may contain user content ({@code ck_conversation_erasure}).
     *
     * @param now current time
     */
    public void markErased(Instant now) {
        if (status == ConversationStatus.ERASED) {
            return;
        }
        status = ConversationStatus.ERASED;
        erasedAt = UtcTimes.micros(now);
        title = null;
    }

    /**
     * Fails unless the conversation accepts messages.
     *
     * @throws IllegalStateException if the conversation is closed or erased
     */
    public void requireActive() {
        if (status != ConversationStatus.ACTIVE) {
            throw new IllegalStateException("conversation " + id + " is " + status);
        }
    }

    /** @return conversation id */
    public UUID getId() {
        return id;
    }

    /** @return workspace id */
    public UUID getWorkspaceId() {
        return workspaceId;
    }

    /** @return agent resource id, if any */
    public @Nullable UUID getAgentResourceId() {
        return agentResourceId;
    }

    /** @return owner */
    public UUID getPrincipalId() {
        return principalId;
    }

    /** @return entry channel */
    public Channel getChannel() {
        return channel;
    }

    /** @return key hash */
    public String getConversationKeyHash() {
        return conversationKeyHash;
    }

    /** @return title, if any */
    public @Nullable String getTitle() {
        return title;
    }

    /** @return status */
    public ConversationStatus getStatus() {
        return status;
    }

    /** @return start */
    public Instant getStartedAt() {
        return startedAt;
    }

    /** @return last activity */
    public Instant getLastActivityAt() {
        return lastActivityAt;
    }

    /** @return end of retention */
    public Instant getRetentionUntil() {
        return retentionUntil;
    }

    /** @return erasure time, if erased */
    public @Nullable Instant getErasedAt() {
        return erasedAt;
    }

    /** @return optimistic-lock version */
    public long getRowVersion() {
        return rowVersion;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof Conversation other && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Conversation[" + id + ", " + status + "]";
    }
}
