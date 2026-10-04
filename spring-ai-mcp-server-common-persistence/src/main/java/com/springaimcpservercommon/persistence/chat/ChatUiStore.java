package com.springaimcpservercommon.persistence.chat;

import com.springaimcpservercommon.persistence.support.Checks;
import com.springaimcpservercommon.persistence.support.StoreSupport;
import com.springaimcpservercommon.persistence.unit.DaiStore;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * PostgreSQL store for interactive chat state (migration V14, LLD-13 §3): components shown to users with their
 * answers, and like/dislike feedback per answer. Every operation is one transaction and nothing is held in server
 * memory, so any replica serves a reload (ADR-0021). Writes are native statements that stay correct under races:
 * a component is recorded once ({@code ON CONFLICT DO NOTHING}), an answer is written once (conditional update),
 * feedback is an upsert. Texts must already be redacted.
 */
@NullMarked
public final class ChatUiStore {

    /** Largest payload or answer accepted (characters). */
    public static final int MAX_JSON = 100_000;
    /** Longest feedback comment. */
    public static final int MAX_COMMENT = 2_000;
    /** Most components or feedback rows returned for one conversation. */
    public static final int MAX_ROWS = 1_000;
    /** Largest batch for {@link #purgeExpired(int)}. */
    public static final int MAX_PURGE_BATCH = 10_000;

    private static final Pattern COMPONENT_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private static final Pattern COMPONENT_TYPE = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final Pattern REASON = Pattern.compile("[a-z][a-z0-9_]{0,39}");

    /**
     * A component that was shown.
     *
     * @param conversationKey {@code sha256:<hex>} of the conversation key
     * @param workspaceId     agent workspace
     * @param agentId         agent resource id
     * @param principalId     the user it was shown to
     * @param turnId          turn that showed it
     * @param componentId     id within the turn
     * @param componentType   e.g. {@code choice}
     * @param payloadJson     payload as shown (redacted JSON object)
     */
    public record NewInteraction(String conversationKey, UUID workspaceId, UUID agentId, UUID principalId,
                                 UUID turnId, String componentId, String componentType, String payloadJson) {
        /** Validates the components. */
        public NewInteraction {
            Checks.sha256(conversationKey, "conversationKey");
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(principalId, "principalId");
            Objects.requireNonNull(turnId, "turnId");
            Checks.matches(componentId, COMPONENT_ID, "componentId");
            Checks.matches(componentType, COMPONENT_TYPE, "componentType");
            Checks.text(payloadJson, "payloadJson", MAX_JSON);
        }
    }

    /**
     * Feedback to store.
     *
     * @param conversationKey {@code sha256:<hex>} of the conversation key
     * @param workspaceId     agent workspace
     * @param agentId         agent resource id
     * @param principalId     the user giving it
     * @param turnId          the answer's turn
     * @param rating          like/dislike
     * @param reason          optional reason code ({@code [a-z][a-z0-9_]{0,39}})
     * @param comment         optional redacted comment (≤ {@value #MAX_COMMENT} chars)
     */
    public record NewFeedback(String conversationKey, UUID workspaceId, UUID agentId, UUID principalId,
                              UUID turnId, TurnFeedback.Rating rating, @Nullable String reason,
                              @Nullable String comment) {
        /** Validates the components. */
        public NewFeedback {
            Checks.sha256(conversationKey, "conversationKey");
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(principalId, "principalId");
            Objects.requireNonNull(turnId, "turnId");
            Objects.requireNonNull(rating, "rating");
            Checks.optionalMatches(reason, REASON, "reason");
            Checks.optionalText(comment, "comment", MAX_COMMENT);
        }
    }

    private final StoreSupport db;
    private final Clock clock;

    /**
     * Creates the store.
     *
     * @param store the framework's persistence unit
     * @param clock time source
     */
    public ChatUiStore(DaiStore store, Clock clock) {
        this.db = new StoreSupport(store);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Records a shown component; a second record of the same (conversation, turn, component) is ignored.
     *
     * @param interaction the component
     * @param ttl         how long it is kept
     * @return {@code true} when a row was inserted
     */
    public boolean recordShown(NewInteraction interaction, Duration ttl) {
        Objects.requireNonNull(interaction, "interaction");
        String payload = Checks.optionalJsonObject(interaction.payloadJson(), "payloadJson");
        Instant now = clock.instant();
        Instant expires = now.plus(positive(ttl));
        return db.write(em -> em.createNativeQuery("INSERT INTO " + db.qualified("dai_chat_interaction")
                        + " (conversation_key, workspace_id, agent_id, principal_id, turn_id, component_id,"
                        + " component_type, payload, created_at, expires_at)"
                        + " VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, CAST(?8 AS jsonb), ?9, ?10)"
                        + " ON CONFLICT (conversation_key, turn_id, component_id) DO NOTHING")
                .setParameter(1, interaction.conversationKey())
                .setParameter(2, interaction.workspaceId())
                .setParameter(3, interaction.agentId())
                .setParameter(4, interaction.principalId())
                .setParameter(5, interaction.turnId())
                .setParameter(6, interaction.componentId())
                .setParameter(7, interaction.componentType())
                .setParameter(8, payload)
                .setParameter(9, now)
                .setParameter(10, expires)
                .executeUpdate()) == 1;
    }

    /**
     * Finds a live component of a conversation.
     *
     * @param conversationKey {@code sha256:<hex>} of the conversation key
     * @param turnId          turn that showed it
     * @param componentId     id within the turn
     * @return the component, if this conversation showed it and it has not expired
     */
    public Optional<ChatInteraction> find(String conversationKey, UUID turnId, String componentId) {
        Checks.sha256(conversationKey, "conversationKey");
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(componentId, "componentId");
        Instant now = clock.instant();
        return db.read(em -> em.createQuery("select i from ChatInteraction i where i.conversationKey = :key "
                        + "and i.turnId = :turn and i.componentId = :component and i.expiresAt > :now",
                        ChatInteraction.class)
                .setParameter("key", conversationKey)
                .setParameter("turn", turnId)
                .setParameter("component", componentId)
                .setParameter("now", now)
                .getResultStream().findFirst());
    }

    /**
     * Stores the answer to a component, once.
     *
     * @param conversationKey {@code sha256:<hex>} of the conversation key
     * @param turnId          turn that showed it
     * @param componentId     id within the turn
     * @param answerJson      validated answer (JSON object)
     * @return {@code false} when the component does not exist, expired or was already answered
     */
    public boolean answer(String conversationKey, UUID turnId, String componentId, String answerJson) {
        Checks.sha256(conversationKey, "conversationKey");
        Objects.requireNonNull(turnId, "turnId");
        Checks.matches(componentId, COMPONENT_ID, "componentId");
        Checks.text(answerJson, "answerJson", MAX_JSON);
        String answer = Checks.optionalJsonObject(answerJson, "answerJson");
        Instant now = clock.instant();
        return db.write(em -> em.createNativeQuery("UPDATE " + db.qualified("dai_chat_interaction")
                        + " SET answer = CAST(?1 AS jsonb), answered_at = ?2"
                        + " WHERE conversation_key = ?3 AND turn_id = ?4 AND component_id = ?5"
                        + " AND answered_at IS NULL AND expires_at > ?2")
                .setParameter(1, answer)
                .setParameter(2, now)
                .setParameter(3, conversationKey)
                .setParameter(4, turnId)
                .setParameter(5, componentId)
                .executeUpdate()) == 1;
    }

    /**
     * Stores or replaces feedback on an answer and slides its expiry.
     *
     * @param feedback the feedback
     * @param ttl      how long it is kept from now
     */
    public void putFeedback(NewFeedback feedback, Duration ttl) {
        Objects.requireNonNull(feedback, "feedback");
        Instant now = clock.instant();
        Instant expires = now.plus(positive(ttl));
        db.writeVoid(em -> em.createNativeQuery("INSERT INTO " + db.qualified("dai_turn_feedback")
                        + " (conversation_key, workspace_id, agent_id, principal_id, turn_id, rating, reason, comment,"
                        + " created_at, updated_at, expires_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6, CAST(?7 AS text), CAST(?8 AS text), ?9, ?9, ?10)"
                        + " ON CONFLICT (conversation_key, turn_id) DO UPDATE SET rating = EXCLUDED.rating,"
                        + " reason = EXCLUDED.reason, comment = EXCLUDED.comment, updated_at = EXCLUDED.updated_at,"
                        + " expires_at = EXCLUDED.expires_at")
                .setParameter(1, feedback.conversationKey())
                .setParameter(2, feedback.workspaceId())
                .setParameter(3, feedback.agentId())
                .setParameter(4, feedback.principalId())
                .setParameter(5, feedback.turnId())
                .setParameter(6, feedback.rating().name())
                .setParameter(7, feedback.reason())
                .setParameter(8, feedback.comment())
                .setParameter(9, now)
                .setParameter(10, expires)
                .executeUpdate());
    }

    /**
     * Withdraws feedback on an answer.
     *
     * @param conversationKey {@code sha256:<hex>} of the conversation key
     * @param turnId          the answer's turn
     * @return {@code true} when feedback existed
     */
    public boolean clearFeedback(String conversationKey, UUID turnId) {
        Checks.sha256(conversationKey, "conversationKey");
        Objects.requireNonNull(turnId, "turnId");
        return db.write(em -> em.createNativeQuery("DELETE FROM " + db.qualified("dai_turn_feedback")
                        + " WHERE conversation_key = ?1 AND turn_id = ?2")
                .setParameter(1, conversationKey)
                .setParameter(2, turnId)
                .executeUpdate()) == 1;
    }

    /**
     * Live components of a conversation, oldest first.
     *
     * @param conversationKey {@code sha256:<hex>} of the conversation key
     * @return at most {@value #MAX_ROWS} components
     */
    public List<ChatInteraction> interactions(String conversationKey) {
        Checks.sha256(conversationKey, "conversationKey");
        Instant now = clock.instant();
        return db.read(em -> em.createQuery("select i from ChatInteraction i where i.conversationKey = :key "
                        + "and i.expiresAt > :now order by i.createdAt, i.componentId", ChatInteraction.class)
                .setParameter("key", conversationKey)
                .setParameter("now", now)
                .setMaxResults(MAX_ROWS)
                .getResultList());
    }

    /**
     * Live feedback of a conversation.
     *
     * @param conversationKey {@code sha256:<hex>} of the conversation key
     * @return at most {@value #MAX_ROWS} rows
     */
    public List<TurnFeedback> feedback(String conversationKey) {
        Checks.sha256(conversationKey, "conversationKey");
        Instant now = clock.instant();
        return db.read(em -> em.createQuery("select f from TurnFeedback f where f.conversationKey = :key "
                        + "and f.expiresAt > :now order by f.updatedAt", TurnFeedback.class)
                .setParameter("key", conversationKey)
                .setParameter("now", now)
                .setMaxResults(MAX_ROWS)
                .getResultList());
    }

    /**
     * Deletes expired components and feedback. Bounded per call so a backlog never holds a long transaction.
     *
     * @param batchSize most rows to delete per table, 1–{@value #MAX_PURGE_BATCH}
     * @return rows deleted
     */
    public int purgeExpired(int batchSize) {
        if (batchSize < 1 || batchSize > MAX_PURGE_BATCH) {
            throw new IllegalArgumentException("batchSize must be between 1 and " + MAX_PURGE_BATCH);
        }
        Instant now = clock.instant();
        int deleted = 0;
        for (String name : List.of("dai_chat_interaction", "dai_turn_feedback")) {
            String table = db.qualified(name);
            deleted += db.write(em -> em.createNativeQuery("DELETE FROM " + table + " WHERE id IN (SELECT id FROM "
                            + table + " WHERE expires_at <= ?1 ORDER BY expires_at LIMIT ?2 FOR UPDATE SKIP LOCKED)")
                    .setParameter(1, now)
                    .setParameter(2, batchSize)
                    .executeUpdate());
        }
        return deleted;
    }

    private static Duration positive(Duration ttl) {
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        return ttl;
    }
}
