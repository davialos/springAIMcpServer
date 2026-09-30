package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.hash.Sha256;
import com.springaimcpservercommon.core.lint.SecretScanner;
import com.springaimcpservercommon.persistence.memory.ChatMemoryMessage.Role;
import com.springaimcpservercommon.persistence.memory.ChatMemoryStore;
import com.springaimcpservercommon.persistence.memory.ChatMemoryStore.Entry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoreChatMemoryRepositoryTest {

    private static final Duration RETENTION = Duration.ofHours(6);

    private final ChatMemoryStore store = mock(ChatMemoryStore.class);
    private final StoreChatMemoryRepository repository = new StoreChatMemoryRepository(store,
            new MessageRedactor(new SecretScanner(), 200), RETENTION);

    @SuppressWarnings("unchecked")
    private List<Entry> saved(String conversationId, List<Message> messages) {
        repository.saveAll(conversationId, messages);
        ArgumentCaptor<List<Entry>> captor = ArgumentCaptor.forClass(List.class);
        verify(store).replace(eq(Sha256.of(conversationId)), captor.capture(), eq(RETENTION));
        return captor.getValue();
    }

    @Test
    void theConversationIdIsOnlyEverStoredAsItsHash() {
        List<Entry> entries = saved("ws:agent:user:conv", List.of(new UserMessage("hi")));

        assertThat(entries).containsExactly(new Entry(Role.USER, "hi"));
    }

    @Test
    void userAssistantAndSystemTextIsKeptAndToolTrafficAndBlanksAreNot() {
        Message toolCallOnly = AssistantMessage.builder().content("").build();
        Message toolResponse = mock(Message.class);
        when(toolResponse.getMessageType()).thenReturn(MessageType.TOOL);
        when(toolResponse.getText()).thenReturn("{\"rows\":[]}");

        List<Entry> entries = saved("c1", List.of(new SystemMessage("be brief"), new UserMessage("q"),
                toolCallOnly, toolResponse, new AssistantMessage("a")));

        assertThat(entries).containsExactly(new Entry(Role.SYSTEM, "be brief"), new Entry(Role.USER, "q"),
                new Entry(Role.ASSISTANT, "a"));
    }

    @Test
    void secretsAreRedactedBeforeTheyReachTheStore() {
        List<Entry> entries = saved("c2",
                List.of(new UserMessage("my key is sk-abcdefghijklmnopqrstuvwxyz123456, use it")));

        assertThat(entries).singleElement().satisfies(e ->
                assertThat(e.content()).doesNotContain("sk-abcdefghijklmnopqrstuvwxyz123456"));
    }

    @Test
    void loadingMapsStoredRolesBackToMessages() {
        when(store.load(Sha256.of("c3"))).thenReturn(List.of(new Entry(Role.USER, "q"),
                new Entry(Role.ASSISTANT, "a"), new Entry(Role.SYSTEM, "s")));

        List<Message> messages = repository.findByConversationId("c3");

        assertThat(messages).extracting(Message::getMessageType)
                .containsExactly(MessageType.USER, MessageType.ASSISTANT, MessageType.SYSTEM);
        assertThat(messages).extracting(Message::getText).containsExactly("q", "a", "s");
    }

    @Test
    void deleteAndListingBehaveAsDocumented() {
        repository.deleteByConversationId("c4");

        verify(store).delete(Sha256.of("c4"));
        assertThat(repository.findConversationIds()).isEmpty();
        verify(store, org.mockito.Mockito.never()).replace(any(), any(), any());
    }
}
