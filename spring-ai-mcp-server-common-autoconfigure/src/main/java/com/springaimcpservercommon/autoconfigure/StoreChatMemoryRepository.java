package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.hash.Sha256;
import com.springaimcpservercommon.persistence.memory.ChatMemoryMessage.Role;
import com.springaimcpservercommon.persistence.memory.ChatMemoryStore;
import com.springaimcpservercommon.persistence.memory.ChatMemoryStore.Entry;
import org.jspecify.annotations.NullMarked;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link ChatMemoryRepository} over PostgreSQL ({@link ChatMemoryStore}), so a follow-up turn sees the same memory on
 * any replica and after a restart (OQ-45, ADR-0021). Wrapped by Spring AI's {@code MessageWindowChatMemory}, which
 * decides the window; this class only stores it.
 *
 * <p>Conversation ids are stored as their SHA-256 (they embed workspace, agent and principal, so a guessed id alone
 * reaches nothing). Text is redacted before it is stored, like transcripts. Only user, assistant and system text is
 * kept: tool-call traffic lives inside one turn's tool loop and is not memory. Because ids are stored only as
 * hashes, {@link #findConversationIds()} cannot list them and returns an empty list; nothing in the framework needs
 * it.
 */
@NullMarked
final class StoreChatMemoryRepository implements ChatMemoryRepository {

    private final ChatMemoryStore store;
    private final MessageRedactor redactor;
    private final Duration retention;

    StoreChatMemoryRepository(ChatMemoryStore store, MessageRedactor redactor, Duration retention) {
        this.store = Objects.requireNonNull(store, "store");
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        this.retention = Objects.requireNonNull(retention, "retention");
    }

    @Override
    public List<String> findConversationIds() {
        return List.of();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        List<Message> messages = new ArrayList<>();
        for (Entry entry : store.load(key(conversationId))) {
            messages.add(switch (entry.role()) {
                case USER -> new UserMessage(entry.content());
                case ASSISTANT -> new AssistantMessage(entry.content());
                case SYSTEM -> new SystemMessage(entry.content());
            });
        }
        return messages;
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        List<Entry> entries = new ArrayList<>(messages.size());
        for (Message message : messages) {
            Role role = switch (message.getMessageType()) {
                case USER -> Role.USER;
                case ASSISTANT -> Role.ASSISTANT;
                case SYSTEM -> Role.SYSTEM;
                default -> null;
            };
            String text = message.getText();
            if (role == null || text == null || text.isBlank()) {
                continue;
            }
            entries.add(new Entry(role, redactor.apply(text).content()));
        }
        int from = Math.max(0, entries.size() - ChatMemoryStore.MAX_MESSAGES);
        store.replace(key(conversationId), entries.subList(from, entries.size()), retention);
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        store.delete(key(conversationId));
    }

    private static String key(String conversationId) {
        return Sha256.of(conversationId);
    }
}
