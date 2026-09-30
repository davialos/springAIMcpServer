package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.persistence.audit.AuditCategory;
import com.springaimcpservercommon.persistence.audit.AuditPlane;
import com.springaimcpservercommon.persistence.memory.ChatMemoryStore;
import com.springaimcpservercommon.persistence.support.PageRequest;
import com.springaimcpservercommon.persistence.support.Slice;
import com.springaimcpservercommon.core.invocation.Channel;
import com.springaimcpservercommon.persistence.telemetry.Conversation;
import com.springaimcpservercommon.persistence.telemetry.ConversationStatus;
import com.springaimcpservercommon.persistence.telemetry.TelemetryStore;
import com.springaimcpservercommon.security.permission.Permission;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Erase keeps the transcript for audit by default; auditors can read it (audited) and admins can purge it. */
class ConversationAuditRetentionTest {

    private final TelemetryStore store = mock(TelemetryStore.class);
    private final AdminAudit audit = mock(AdminAudit.class);
    private final AdminApi api = mock(AdminApi.class);
    private final ChatMemoryStore memory = mock(ChatMemoryStore.class);
    private final DaiPrincipal user = mock(DaiPrincipal.class);
    private final DaiPrincipal auditor = mock(DaiPrincipal.class);
    private final HttpServletRequest request = new MockHttpServletRequest();
    private final UUID workspace = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();

    private Conversation conversation(ConversationStatus status, UUID inWorkspace) {
        Conversation c = mock(Conversation.class);
        when(c.getId()).thenReturn(conversationId);
        when(c.getWorkspaceId()).thenReturn(inWorkspace);
        when(c.getPrincipalId()).thenReturn(userId);
        when(c.getAgentResourceId()).thenReturn(UUID.randomUUID());
        when(c.getChannel()).thenReturn(Channel.CHAT);
        when(c.getTitle()).thenReturn("Refund question");
        when(c.getStatus()).thenReturn(status);
        when(c.getStartedAt()).thenReturn(Instant.EPOCH);
        when(c.getLastActivityAt()).thenReturn(Instant.EPOCH);
        when(c.getRetentionUntil()).thenReturn(Instant.EPOCH.plusSeconds(60));
        return c;
    }

    private ConversationController userController(Duration hold) {
        when(user.principalId()).thenReturn(userId);
        when(api.authenticated(any())).thenReturn(new AdminApi.Gate(user, null));
        return new ConversationController(store, audit, api, memory, hold);
    }

    private ConversationAuditController auditController() {
        when(api.gate(any(), any(Permission.class), eq(workspace))).thenReturn(new AdminApi.Gate(auditor, null));
        return new ConversationAuditController(store, audit, api, new ConversationController(store, audit, api, memory));
    }

    @Test
    void theDefaultEraseModeIsRetainForAuditForNinetyDays() {
        DaiProperties.Conversations c = new DaiProperties.Conversations(false, Duration.ofDays(30), 100_000,
                Duration.ofMinutes(15), DaiProperties.Conversations.EraseMode.RETAIN_FOR_AUDIT, Duration.ofDays(90));
        assertThat(c.eraseMode()).isEqualTo(DaiProperties.Conversations.EraseMode.RETAIN_FOR_AUDIT);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new DaiProperties.Conversations(false,
                Duration.ofDays(30), 100_000, Duration.ofMinutes(15),
                DaiProperties.Conversations.EraseMode.HARD, Duration.ZERO))
                .hasMessageContaining("audit-retention");
    }

    @Test
    void aUsersEraseKeepsTheTranscriptForAuditButForgetsTheModelMemory() {
        Conversation c = conversation(ConversationStatus.ACTIVE, workspace);
        when(store.findConversationById(conversationId)).thenReturn(Optional.of(c));
        when(store.eraseConversationKeepingForAudit(conversationId, Duration.ofDays(90))).thenReturn(true);

        var response = userController(Duration.ofDays(90)).erase(conversationId, request);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(store, never()).eraseConversation(any());
        verify(memory, org.mockito.Mockito.atLeastOnce()).delete(anyString());
        verify(audit).record(eq(user), eq(AuditCategory.DATA_WRITE), eq(AuditPlane.DATA), eq("CONVERSATION_ERASED"),
                eq(workspace), eq("conversation"), eq(conversationId.toString()), isNull(), isNull(),
                eq(Map.of("mode", "RETAIN_FOR_AUDIT")));
    }

    @Test
    void hardModeDeletesTheTranscriptAtOnce() {
        Conversation c = conversation(ConversationStatus.ACTIVE, workspace);
        when(store.findConversationById(conversationId)).thenReturn(Optional.of(c));
        when(store.eraseConversation(conversationId)).thenReturn(true);

        assertThat(userController(null).erase(conversationId, request).getStatusCode().value()).isEqualTo(204);
        verify(store, never()).eraseConversationKeepingForAudit(any(), any());
    }

    @Test
    void theUserNoLongerSeesTheTitleOfAnErasedConversation() {
        assertThat(ConversationController.ConversationDto.of(conversation(ConversationStatus.ERASED, workspace))
                .title()).isNull();
        assertThat(ConversationController.ConversationDto.of(conversation(ConversationStatus.ACTIVE, workspace))
                .title()).isEqualTo("Refund question");
    }

    @Test
    void auditorsListErasedConversationsWithTheirHold() {
        Conversation c = conversation(ConversationStatus.ERASED, workspace);
        when(store.conversationsOfWorkspace(eq(workspace), eq(ConversationStatus.ERASED), isNull(), any()))
                .thenReturn(new Slice<>(List.of(c), PageRequest.first(50), false));

        var body = (ConversationAuditController.AuditPageDto) auditController()
                .list(workspace, "erased", null, null, null, request).getBody();

        assertThat(body.items()).singleElement().satisfies(d -> {
            assertThat(d.status()).isEqualTo("ERASED");
            assertThat(d.title()).isEqualTo("Refund question");
        });
    }

    @Test
    void readingATranscriptIsItselfAudited() {
        Conversation c = conversation(ConversationStatus.ERASED, workspace);
        when(store.findConversationById(conversationId)).thenReturn(Optional.of(c));
        when(store.messages(conversationId, 500)).thenReturn(List.of());

        assertThat(auditController().transcript(workspace, conversationId, null, request).getStatusCode().value())
                .isEqualTo(200);
        verify(audit).record(eq(auditor), eq(AuditCategory.ADMIN), eq(AuditPlane.DATA), eq("CONVERSATION_READ"),
                eq(workspace), eq("conversation"), eq(conversationId.toString()), isNull(), isNull(), anyMap());
    }

    @Test
    void aConversationOfAnotherWorkspaceIsNotFoundAndNothingIsAudited() {
        Conversation c = conversation(ConversationStatus.ERASED, UUID.randomUUID());
        when(store.findConversationById(conversationId)).thenReturn(Optional.of(c));

        assertThat(auditController().transcript(workspace, conversationId, null, request).getStatusCode().value())
                .isEqualTo(404);
        verify(store, never()).messages(any(), org.mockito.ArgumentMatchers.anyInt());
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void anAdminPurgeDeletesAtOnceForgetsMemoryAndRecordsTheReason() {
        Conversation c = conversation(ConversationStatus.ERASED, workspace);
        when(store.findConversationById(conversationId)).thenReturn(Optional.of(c));
        when(store.purgeConversation(conversationId)).thenReturn(true);

        assertThat(auditController().purge(workspace, conversationId, "DSR-42", request).getStatusCode().value())
                .isEqualTo(204);
        verify(api).gate(any(), eq(Permission.WORKSPACE_ADMIN), eq(workspace));
        verify(memory, org.mockito.Mockito.atLeastOnce()).delete(anyString());
        verify(audit).record(eq(auditor), eq(AuditCategory.DATA_WRITE), eq(AuditPlane.DATA),
                eq("CONVERSATION_PURGED"), eq(workspace), eq("conversation"), eq(conversationId.toString()),
                isNull(), eq("DSR-42"), anyMap());
    }
}
