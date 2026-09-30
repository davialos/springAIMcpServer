package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.persistence.audit.AuditCategory;
import com.springaimcpservercommon.persistence.audit.AuditPlane;
import com.springaimcpservercommon.persistence.support.PageRequest;
import com.springaimcpservercommon.persistence.telemetry.Conversation;
import com.springaimcpservercommon.persistence.telemetry.ConversationMessage;
import com.springaimcpservercommon.persistence.telemetry.ConversationStatus;
import com.springaimcpservercommon.persistence.telemetry.TelemetryStore;
import com.springaimcpservercommon.security.permission.Permission;
import com.springaimcpservercommon.webmvc.problem.ProblemCode;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Auditor access to the stored conversations of a workspace (LLD-06 §7), including those their users erased but
 * that are kept for audit ({@code dynamic.ai.agent.conversations.erase-mode=RETAIN_FOR_AUDIT}, the default).
 *
 * <ul>
 *   <li>Listing and reading need {@link Permission#AUDIT_READ} on the workspace. Every transcript read is itself
 *       recorded in the audit trail ({@code CONVERSATION_READ}): who looked at whose conversation.</li>
 *   <li>Purging needs {@link Permission#WORKSPACE_ADMIN}: it deletes a conversation and its model memory at once,
 *       hold or not, for a data-subject erasure request ({@code CONVERSATION_PURGED}).</li>
 * </ul>
 * Messages are the stored, already redacted text. Not a {@code @Component}; registered by
 * {@link DaiAdminAutoConfiguration}.
 */
@NullMarked
@RequestMapping("/dynamic-ai/admin/api/v1/workspaces/{workspaceId}/conversations")
public final class ConversationAuditController {

    static final int DEFAULT_MESSAGES = 500;
    static final int MAX_MESSAGES = 10_000;

    /**
     * A conversation as auditors see it.
     *
     * @param id              id
     * @param principalId     the user
     * @param agentResourceId agent, if any
     * @param channel         channel
     * @param title           title, if any
     * @param status          ACTIVE, CLOSED or ERASED
     * @param startedAt       start
     * @param lastActivityAt  last activity
     * @param erasedAt        when the user erased it, if erased
     * @param auditHoldUntil  end of the audit hold of an erased conversation, if held
     * @param retentionUntil  when the purge job deletes it
     */
    public record AuditConversationDto(UUID id, UUID principalId, @Nullable UUID agentResourceId, String channel,
                                       @Nullable String title, String status, Instant startedAt,
                                       Instant lastActivityAt, @Nullable Instant erasedAt,
                                       @Nullable Instant auditHoldUntil, Instant retentionUntil) {
        static AuditConversationDto of(Conversation c) {
            return new AuditConversationDto(c.getId(), c.getPrincipalId(), c.getAgentResourceId(),
                    c.getChannel().name(), c.getTitle(), c.getStatus().name(), c.getStartedAt(),
                    c.getLastActivityAt(), c.getErasedAt(), c.getAuditHoldUntil(), c.getRetentionUntil());
        }
    }

    /**
     * A page of conversations.
     *
     * @param items   conversations, most recently active first
     * @param limit   page size
     * @param offset  page offset
     * @param hasMore whether more pages exist
     */
    public record AuditPageDto(List<AuditConversationDto> items, int limit, int offset, boolean hasMore) {}

    /**
     * One stored message.
     *
     * @param seq       position
     * @param role      USER, ASSISTANT, TOOL or SYSTEM
     * @param content   stored (redacted) text
     * @param redacted  whether redaction changed it
     * @param turnId    turn that produced it, if any
     * @param createdAt when it was stored
     */
    public record AuditMessageDto(int seq, String role, String content, boolean redacted, @Nullable UUID turnId,
                                  Instant createdAt) {
        static AuditMessageDto of(ConversationMessage m) {
            return new AuditMessageDto(m.getSeq(), m.getRole().name(), m.getContent(), m.isRedacted(),
                    m.getTurnId(), m.getCreatedAt());
        }
    }

    /**
     * A transcript.
     *
     * @param conversation the conversation
     * @param messages     its messages, oldest first
     */
    public record TranscriptDto(AuditConversationDto conversation, List<AuditMessageDto> messages) {}

    private final TelemetryStore store;
    private final AdminAudit audit;
    private final AdminApi api;
    private final ConversationController memoryEraser;

    ConversationAuditController(TelemetryStore store, AdminAudit audit, AdminApi api,
                                ConversationController memoryEraser) {
        this.store = Objects.requireNonNull(store, "store");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.api = Objects.requireNonNull(api, "api");
        this.memoryEraser = Objects.requireNonNull(memoryEraser, "memoryEraser");
    }

    /**
     * Lists the workspace's conversations.
     *
     * @param workspaceId workspace
     * @param status      only ACTIVE, CLOSED or ERASED
     * @param principalId only this user's
     * @param limit       page size
     * @param offset      page offset
     * @param request     current request
     * @return 200 with a page
     */
    @GetMapping
    public ResponseEntity<?> list(@PathVariable UUID workspaceId,
                                  @RequestParam(required = false) @Nullable String status,
                                  @RequestParam(required = false) @Nullable UUID principalId,
                                  @RequestParam(required = false) @Nullable Integer limit,
                                  @RequestParam(required = false) @Nullable Integer offset,
                                  HttpServletRequest request) {
        var gate = api.gate(request, Permission.AUDIT_READ, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        ConversationStatus filter = null;
        if (status != null) {
            try {
                filter = ConversationStatus.valueOf(status.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("status must be ACTIVE, CLOSED or ERASED");
            }
        }
        PageRequest page = AdminApi.page(limit, offset);
        var slice = store.conversationsOfWorkspace(workspaceId, filter, principalId, page);
        return ResponseEntity.ok(new AuditPageDto(slice.items().stream().map(AuditConversationDto::of).toList(),
                slice.page().limit(), slice.page().offset(), slice.hasMore()));
    }

    /**
     * Reads a transcript; the read is recorded in the audit trail.
     *
     * @param workspaceId workspace
     * @param id          conversation
     * @param limit       newest messages to return (1..10000, default 500)
     * @param request     current request
     * @return 200 with the transcript; 404 when unknown or in another workspace
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> transcript(@PathVariable UUID workspaceId, @PathVariable UUID id,
                                        @RequestParam(required = false) @Nullable Integer limit,
                                        HttpServletRequest request) {
        var gate = api.gate(request, Permission.AUDIT_READ, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        int max = limit == null ? DEFAULT_MESSAGES : limit;
        if (max < 1 || max > MAX_MESSAGES) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_MESSAGES);
        }
        Conversation c = inWorkspace(workspaceId, id);
        if (c == null) {
            return AdminApi.problem(ProblemCode.NOT_FOUND, "Conversation not found", null, request);
        }
        List<AuditMessageDto> messages = store.messages(id, max).stream().map(AuditMessageDto::of).toList();
        DaiPrincipal caller = gate.caller();
        audit.record(caller, AuditCategory.ADMIN, AuditPlane.DATA, "CONVERSATION_READ", workspaceId,
                "conversation", id.toString(), null, null,
                Map.of("status", c.getStatus().name(), "messages", messages.size()));
        return ResponseEntity.ok(new TranscriptDto(AuditConversationDto.of(c), messages));
    }

    /**
     * Deletes a conversation, its messages and its model memory now, regardless of any audit hold.
     *
     * @param workspaceId workspace
     * @param id          conversation
     * @param reason      why (for example a data-subject request reference); recorded in the audit trail
     * @param request     current request
     * @return 204 when purged; 404 when unknown or in another workspace
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> purge(@PathVariable UUID workspaceId, @PathVariable UUID id,
                                   @RequestParam(required = false) @Nullable String reason,
                                   HttpServletRequest request) {
        var gate = api.gate(request, Permission.WORKSPACE_ADMIN, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        if (reason != null && reason.length() > 500) {
            throw new IllegalArgumentException("reason must be at most 500 characters");
        }
        Conversation c = inWorkspace(workspaceId, id);
        if (c == null || !store.purgeConversation(id)) {
            return AdminApi.problem(ProblemCode.NOT_FOUND, "Conversation not found", null, request);
        }
        memoryEraser.forgetMemory(c);
        audit.record(gate.caller(), AuditCategory.DATA_WRITE, AuditPlane.DATA, "CONVERSATION_PURGED", workspaceId,
                "conversation", id.toString(), null, reason, Map.of("status", c.getStatus().name()));
        return ResponseEntity.noContent().build();
    }

    private @Nullable Conversation inWorkspace(UUID workspaceId, UUID id) {
        return store.findConversationById(id).filter(c -> c.getWorkspaceId().equals(workspaceId)).orElse(null);
    }
}
