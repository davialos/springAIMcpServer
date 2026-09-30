package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.runtime.ConversationKeys;
import com.springaimcpservercommon.core.hash.Sha256;
import com.springaimcpservercommon.persistence.memory.ChatMemoryStore;
import com.springaimcpservercommon.persistence.telemetry.Conversation;
import com.springaimcpservercommon.persistence.telemetry.TelemetryStore;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class ConversationMemoryErasureTest {

    private final ChatMemoryStore memory = mock(ChatMemoryStore.class);
    private final UUID workspace = UUID.randomUUID();
    private final UUID agent = UUID.randomUUID();
    private final UUID principal = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();

    private ConversationController controller(ChatMemoryStore store) {
        return new ConversationController(mock(TelemetryStore.class), mock(AdminAudit.class), mock(AdminApi.class),
                store);
    }

    private Conversation conversation(UUID agentId) {
        Conversation c = mock(Conversation.class);
        when(c.getId()).thenReturn(conversationId);
        when(c.getWorkspaceId()).thenReturn(workspace);
        when(c.getAgentResourceId()).thenReturn(agentId);
        when(c.getPrincipalId()).thenReturn(principal);
        return c;
    }

    @Test
    void closingOrErasingDeletesTheMemoryAndTheSummaryUnderTheInvokersKey() {
        controller(memory).forgetMemory(conversation(agent));

        String key = ConversationKeys.memoryKey(workspace, agent, principal, conversationId);
        verify(memory).delete(Sha256.of(key));
        verify(memory).delete(Sha256.of(ConversationKeys.summaryKey(key)));
    }

    @Test
    void aConversationWithoutAnAgentHasNoMemoryToForget() {
        controller(memory).forgetMemory(conversation(null));

        verify(memory, never()).delete(any());
    }

    @Test
    void withoutAMemoryStoreNothingHappens() {
        controller(null).forgetMemory(conversation(agent));
    }

    @Test
    void aFailingMemoryStoreNeverFailsTheRequest() {
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(memory).delete(any());

        controller(memory).forgetMemory(conversation(agent));
    }
}
