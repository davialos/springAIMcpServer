package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.chat.ChatUiState;
import com.springaimcpservercommon.persistence.chat.ChatInteraction;
import com.springaimcpservercommon.persistence.chat.ChatUiStore;
import com.springaimcpservercommon.persistence.chat.TurnFeedback;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ChatUiState} over PostgreSQL ({@link ChatUiStore}, V13): what a reloaded chat restores on any replica
 * (ADR-0021). Recording a shown component never fails the turn: errors are logged and the component simply cannot be
 * answered through the API later.
 */
@NullMarked
final class StoreChatUiState implements ChatUiState {

    private static final Logger LOG = LoggerFactory.getLogger(StoreChatUiState.class);

    private final ChatUiStore store;
    private final Duration interactionRetention;
    private final Duration feedbackRetention;

    StoreChatUiState(ChatUiStore store, Duration interactionRetention, Duration feedbackRetention) {
        this.store = Objects.requireNonNull(store, "store");
        this.interactionRetention = Objects.requireNonNull(interactionRetention, "interactionRetention");
        this.feedbackRetention = Objects.requireNonNull(feedbackRetention, "feedbackRetention");
    }

    @Override
    public void componentShown(ShownComponent c) {
        try {
            store.recordShown(new ChatUiStore.NewInteraction(c.conversationKey(), c.workspaceId(), c.agentId(),
                    c.principalId(), c.turnId(), c.componentId(), c.componentType(), c.payloadJson()),
                    interactionRetention);
        } catch (RuntimeException e) {
            LOG.warn("Recording component {} of turn {} failed", c.componentId(), c.turnId(), e);
        }
    }

    @Override
    public Optional<StoredComponent> find(String conversationKey, UUID turnId, String componentId) {
        return store.find(conversationKey, turnId, componentId).map(StoreChatUiState::toStored);
    }

    @Override
    public boolean answer(String conversationKey, UUID turnId, String componentId, String answerJson) {
        return store.answer(conversationKey, turnId, componentId, answerJson);
    }

    @Override
    public void feedback(Feedback f) {
        if (f.rating() == null) {
            store.clearFeedback(f.conversationKey(), f.turnId());
            return;
        }
        store.putFeedback(new ChatUiStore.NewFeedback(f.conversationKey(), f.workspaceId(), f.agentId(),
                f.principalId(), f.turnId(), TurnFeedback.Rating.valueOf(f.rating().name()), f.reason(),
                f.comment()), feedbackRetention);
    }

    @Override
    public Snapshot load(String conversationKey) {
        return new Snapshot(
                store.interactions(conversationKey).stream().map(StoreChatUiState::toStored).toList(),
                store.feedback(conversationKey).stream()
                        .map(f -> new StoredFeedback(f.getTurnId(), Rating.valueOf(f.getRating().name()),
                                f.getReason(), f.getUpdatedAt()))
                        .toList());
    }

    private static StoredComponent toStored(ChatInteraction i) {
        return new StoredComponent(i.getTurnId(), i.getComponentId(), i.getComponentType(), i.getPayload(),
                i.getAnswer(), i.getCreatedAt(), i.getAnsweredAt());
    }
}
