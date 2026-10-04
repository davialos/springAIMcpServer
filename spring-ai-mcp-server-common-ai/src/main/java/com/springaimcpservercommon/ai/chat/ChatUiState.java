package com.springaimcpservercommon.ai.chat;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * SPI: keeps the interactive state of a chat — components shown to the user, the user's answers and like/dislike
 * feedback per answer (LLD-13 §3). Everything is keyed by the conversation key hash
 * ({@code sha256} of workspace, agent, principal and conversation, see
 * {@link com.springaimcpservercommon.ai.runtime.ConversationKeys}), so one user can never read or answer another's
 * components even with a guessed conversation id.
 *
 * <p>The PostgreSQL implementation lives in {@code autoconfigure} (ADR-0021: state shared by all replicas). Without
 * the {@code dynamic_ai} store, {@link #NONE} keeps nothing: choices still render and their answers still reach the
 * model, but nothing is validated or restored after a reload, and feedback is not kept.
 *
 * <p>Implementations must be thread-safe. {@link #componentShown} is called from tool threads during a turn and must
 * not fail the turn.
 */
public interface ChatUiState {

    /** Keeps nothing (no {@code dynamic_ai} store). */
    ChatUiState NONE = new ChatUiState() {
        @Override
        public void componentShown(ShownComponent component) {
        }

        @Override
        public Optional<StoredComponent> find(String conversationKey, UUID turnId, String componentId) {
            return Optional.empty();
        }

        @Override
        public boolean answer(String conversationKey, UUID turnId, String componentId, String answerJson) {
            return true;
        }

        @Override
        public void feedback(Feedback feedback) {
        }

        @Override
        public Snapshot load(String conversationKey) {
            return new Snapshot(List.of(), List.of());
        }

        @Override
        public boolean persistent() {
            return false;
        }
    };

    /**
     * A component that was shown to the user.
     *
     * @param conversationKey conversation key hash ({@code sha256:<hex>})
     * @param workspaceId     agent workspace
     * @param agentId         agent resource id
     * @param principalId     the user it was shown to
     * @param turnId          turn that showed it
     * @param componentId     id within the turn
     * @param componentType   e.g. {@code choice}
     * @param payloadJson     the payload as shown (already redacted)
     */
    record ShownComponent(String conversationKey, UUID workspaceId, UUID agentId, UUID principalId, UUID turnId,
                          String componentId, String componentType, String payloadJson) {
        /** Validates the components. */
        public ShownComponent {
            Objects.requireNonNull(conversationKey, "conversationKey");
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(principalId, "principalId");
            Objects.requireNonNull(turnId, "turnId");
            Objects.requireNonNull(componentId, "componentId");
            Objects.requireNonNull(componentType, "componentType");
            Objects.requireNonNull(payloadJson, "payloadJson");
        }
    }

    /**
     * A stored component and its answer.
     *
     * @param turnId        turn that showed it
     * @param componentId   id within the turn
     * @param componentType e.g. {@code choice}
     * @param payloadJson   the payload as shown
     * @param answerJson    the user's answer, or {@code null} while unanswered
     * @param shownAt       when it was shown
     * @param answeredAt    when it was answered, or {@code null}
     */
    record StoredComponent(UUID turnId, String componentId, String componentType, String payloadJson,
                           @Nullable String answerJson, Instant shownAt, @Nullable Instant answeredAt) {
    }

    /** Like or dislike. */
    enum Rating {
        /** The answer was helpful. */
        UP,
        /** The answer was not helpful. */
        DOWN
    }

    /**
     * Feedback on one answer; a {@code null} rating withdraws earlier feedback.
     *
     * @param conversationKey conversation key hash
     * @param workspaceId     agent workspace
     * @param agentId         agent resource id
     * @param principalId     the user giving feedback
     * @param turnId          the answer's turn
     * @param rating          like/dislike, or {@code null} to clear
     * @param reason          optional short reason code chosen in the UI ({@code inaccurate}, {@code incomplete}, …)
     * @param comment         optional free text (stored after PII redaction)
     */
    record Feedback(String conversationKey, UUID workspaceId, UUID agentId, UUID principalId, UUID turnId,
                    @Nullable Rating rating, @Nullable String reason, @Nullable String comment) {
        /** Validates the components. */
        public Feedback {
            Objects.requireNonNull(conversationKey, "conversationKey");
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(principalId, "principalId");
            Objects.requireNonNull(turnId, "turnId");
        }
    }

    /**
     * Stored feedback on one answer.
     *
     * @param turnId    the answer's turn
     * @param rating    like/dislike
     * @param reason    reason code, if any
     * @param updatedAt when it was last changed
     */
    record StoredFeedback(UUID turnId, Rating rating, @Nullable String reason, Instant updatedAt) {
    }

    /**
     * Everything needed to restore a conversation's interactive state in the client.
     *
     * @param components components shown, oldest first, with their answers
     * @param feedback   feedback per turn
     */
    record Snapshot(List<StoredComponent> components, List<StoredFeedback> feedback) {
        /** Copies the lists. */
        public Snapshot {
            components = List.copyOf(components);
            feedback = List.copyOf(feedback);
        }
    }

    /**
     * Records a component shown to the user. Must not throw.
     *
     * @param component what was shown
     */
    void componentShown(ShownComponent component);

    /**
     * Looks up a component of the caller's conversation.
     *
     * @param conversationKey conversation key hash
     * @param turnId          turn that showed it
     * @param componentId     id within the turn
     * @return the component, if this conversation showed it
     */
    Optional<StoredComponent> find(String conversationKey, UUID turnId, String componentId);

    /**
     * Stores the answer to a component, once.
     *
     * @param conversationKey conversation key hash
     * @param turnId          turn that showed it
     * @param componentId     id within the turn
     * @param answerJson      validated answer
     * @return {@code false} when the component was already answered (or does not exist)
     */
    boolean answer(String conversationKey, UUID turnId, String componentId, String answerJson);

    /**
     * Stores, replaces or (with a {@code null} rating) withdraws feedback on an answer.
     *
     * @param feedback the feedback
     */
    void feedback(Feedback feedback);

    /**
     * The interactive state of a conversation.
     *
     * @param conversationKey conversation key hash
     * @return components with answers, and feedback
     */
    Snapshot load(String conversationKey);

    /**
     * Whether state survives the request (a store backs it). The API reports {@code persistent=false} so clients
     * know a reload will not restore answered choices.
     *
     * @return {@code true} when backed by a store
     */
    default boolean persistent() {
        return true;
    }
}
