package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.runtime.ConversationRecorder;
import com.springaimcpservercommon.core.hash.Sha256;
import com.springaimcpservercommon.persistence.telemetry.Conversation;
import com.springaimcpservercommon.persistence.telemetry.MessageRole;
import com.springaimcpservercommon.persistence.telemetry.NewConversation;
import com.springaimcpservercommon.persistence.telemetry.NewMessage;
import com.springaimcpservercommon.persistence.telemetry.TelemetryStore;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;

/**
 * Stores the transcript of successful agent turns in {@code dai_conversation} and {@code dai_conversation_message}
 * so users can see, close and erase their conversations (F-44). Off by default
 * ({@code dynamic.ai.agent.conversations.enabled}); when on, every message is redacted first
 * ({@link MessageRedactor}) and the conversation is kept for the configured retention, which slides with activity
 * and is enforced by {@link ConversationRetentionJob}.
 *
 * <p>The stored conversation takes the id the client uses, and is found again through the hash of
 * (principal, agent, conversation id), so another user who guesses an id neither sees nor writes into it
 * (LLD-06 §7). Writes are best effort on virtual threads behind a bulkhead ({@link BoundedAsyncWriter}); a failed
 * write loses that exchange from the history and nothing else. Nothing is written into a conversation the user
 * has closed or erased: the store refuses, which is logged as a failure and does not affect the turn.
 *
 * <p>This is the history shown to users. It is not the memory the model reads: chat memory is still separate and
 * in-memory (OQ-45).
 */
@NullMarked
final class StoreConversationRecorder implements ConversationRecorder, AutoCloseable {

    static final int MAX_IN_FLIGHT = 32;

    private static final Logger LOG = LoggerFactory.getLogger(StoreConversationRecorder.class);

    private final TelemetryStore store;
    private final MessageRedactor redactor;
    private final Duration retention;
    private final BoundedAsyncWriter writer = new BoundedAsyncWriter(MAX_IN_FLIGHT,
            "dynamic.ai.agent.conversation.record", LOG);

    StoreConversationRecorder(TelemetryStore store, MessageRedactor redactor, Duration retention) {
        this.store = Objects.requireNonNull(store, "store");
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        this.retention = Objects.requireNonNull(retention, "retention");
        if (retention.isNegative() || retention.isZero() || retention.compareTo(Conversation.MAX_RETENTION) > 0) {
            throw new IllegalArgumentException("conversation retention must be positive and at most "
                    + Conversation.MAX_RETENTION.toDays() + " days");
        }
    }

    @Override
    public void record(Exchange e) {
        // Redact on the caller's thread so raw text never sits in the writer queue's closure longer than needed.
        MessageRedactor.Redacted user = redactor.apply(e.userMessage());
        MessageRedactor.Redacted answer = redactor.apply(e.assistantAnswer());
        writer.submit("Recording conversation " + e.conversationId(), () -> {
            String keyHash = Sha256.of(e.principal().principalId() + ":" + e.agent().id() + ":" + e.conversationId());
            Conversation conversation = store.openConversation(new NewConversation(keyHash,
                    e.agent().workspaceId(), e.agent().id(), e.principal().principalId(), e.channel(),
                    redactor.title(user), retention, e.conversationId()));
            store.appendMessage(conversation.getId(),
                    new NewMessage(MessageRole.USER, user.content(), user.redacted(), e.turnId(), null, null));
            store.appendMessage(conversation.getId(),
                    new NewMessage(MessageRole.ASSISTANT, answer.content(), answer.redacted(), e.turnId(), null,
                            null));
            SafeMetrics.count("dynamic.ai.agent.conversation.exchanges.recorded");
        });
    }

    @Override
    public void close() {
        writer.close();
    }
}
