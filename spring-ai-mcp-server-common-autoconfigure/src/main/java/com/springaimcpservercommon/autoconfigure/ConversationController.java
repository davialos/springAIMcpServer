package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.runtime.ConversationKeys;
import com.springaimcpservercommon.core.hash.Sha256;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.persistence.memory.ChatMemoryStore;
import com.springaimcpservercommon.persistence.audit.AuditCategory;
import com.springaimcpservercommon.persistence.audit.AuditPlane;
import com.springaimcpservercommon.persistence.support.PageRequest;
import com.springaimcpservercommon.persistence.telemetry.Conversation;
import com.springaimcpservercommon.persistence.telemetry.ConversationMessage;
import com.springaimcpservercommon.persistence.telemetry.ConversationStatus;
import com.springaimcpservercommon.persistence.telemetry.MessageRole;
import com.springaimcpservercommon.persistence.telemetry.TelemetryStore;
import com.springaimcpservercommon.webmvc.problem.ProblemCode;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Conversation history API for end users (F-44): list my conversations, read my messages, close or erase a
 * conversation. Everything is scoped to the caller: another user's conversation answers 404, and there is no
 * administrative read of message content here.
 *
 * <p>Only {@code USER} and {@code ASSISTANT} messages are returned. Tool results (which can hold row data) and
 * system prompts are never exposed. Erasing deletes the stored messages and marks the conversation
 * {@code ERASED}; the erase is audited without content.
 *
 * <p>Not a {@code @Component}; registered by {@link DaiAdminAutoConfiguration}.
 */
@NullMarked
@RequestMapping("/dynamic-ai/api/conversations")
public final class ConversationController {

    static final int DEFAULT_MESSAGES = 100;
    static final int MAX_MESSAGES = 200;

    /**
     * Conversation row.
     *
     * @param id              id
     * @param workspaceId     workspace
     * @param agentResourceId agent, if any
     * @param channel         channel
     * @param title           title, if any
     * @param status          ACTIVE, CLOSED or ERASED
     * @param startedAt       start
     * @param lastActivityAt  last activity
     */
    public record ConversationDto(UUID id, UUID workspaceId, @Nullable UUID agentResourceId, String channel,
                                  @Nullable String title, String status, Instant startedAt,
                                  Instant lastActivityAt) {
        static ConversationDto of(Conversation c) {
            // an erased conversation kept for audit still has its title; its user no longer sees it
            String title = c.getStatus() == ConversationStatus.ERASED ? null : c.getTitle();
            return new ConversationDto(c.getId(), c.getWorkspaceId(), c.getAgentResourceId(),
                    c.getChannel().name(), title, c.getStatus().name(), c.getStartedAt(),
                    c.getLastActivityAt());
        }
    }

    /**
     * A page of conversations.
     *
     * @param items   conversations, most recently active first
     * @param limit   page size
     * @param offset  page offset
     * @param hasMore whether another page exists
     */
    public record PageDto(List<ConversationDto> items, int limit, int offset, boolean hasMore) {}

    /**
     * Message row.
     *
     * @param seq       position
     * @param role      USER or ASSISTANT
     * @param content   message text (already redacted where the platform redacts it)
     * @param redacted  whether parts were redacted
     * @param turnId    turn, if any
     * @param createdAt time
     */
    public record MessageDto(int seq, String role, String content, boolean redacted, @Nullable UUID turnId,
                             Instant createdAt) {
        static MessageDto of(ConversationMessage m) {
            return new MessageDto(m.getSeq(), m.getRole().name(), m.getContent(), m.isRedacted(), m.getTurnId(),
                    m.getCreatedAt());
        }
    }

    private final TelemetryStore store;
    private final AdminAudit audit;
    private final AdminApi api;
    private final @Nullable ChatMemoryStore memory;
    private final java.time.@Nullable Duration auditHold;

    ConversationController(TelemetryStore store, AdminAudit audit, AdminApi api, @Nullable ChatMemoryStore memory) {
        this(store, audit, api, memory, null);
    }

    /**
     * @param auditHold how long an erased conversation is kept for audit; {@code null} deletes it at once (HARD)
     */
    ConversationController(TelemetryStore store, AdminAudit audit, AdminApi api, @Nullable ChatMemoryStore memory,
                           java.time.@Nullable Duration auditHold) {
        this.memory = memory;
        this.auditHold = auditHold;
        this.store = Objects.requireNonNull(store, "store");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.api = Objects.requireNonNull(api, "api");
    }

    /**
     * Lists the caller's conversations.
     *
     * @param limit   page size (1..200, default 50)
     * @param offset  page offset
     * @param request current request
     * @return 200 with a page
     */
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) @Nullable Integer limit,
                                  @RequestParam(required = false) @Nullable Integer offset,
                                  HttpServletRequest request) {
        var gate = api.authenticated(request);
        if (!gate.open()) {
            return gate.denied();
        }
        PageRequest page = AdminApi.page(limit, offset);
        var slice = store.conversationsOf(gate.caller().principalId(), page);
        return ResponseEntity.ok(new PageDto(slice.items().stream().map(ConversationDto::of).toList(),
                slice.page().limit(), slice.page().offset(), slice.hasMore()));
    }

    /**
     * Returns the newest messages of one of the caller's conversations, oldest first.
     *
     * @param id      conversation
     * @param limit   number of newest messages to load (1..200, default 100); tool and system messages
     *                are loaded but not returned, so fewer may come back
     * @param request current request
     * @return 200 with messages; 404 when unknown, not the caller's, or erased
     */
    @GetMapping("/{id}/messages")
    public ResponseEntity<?> messages(@PathVariable UUID id,
                                      @RequestParam(required = false) @Nullable Integer limit,
                                      HttpServletRequest request) {
        var gate = api.authenticated(request);
        if (!gate.open()) {
            return gate.denied();
        }
        int max = limit == null ? DEFAULT_MESSAGES : limit;
        if (max < 1 || max > MAX_MESSAGES) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_MESSAGES + ": " + max);
        }
        Conversation c = owned(id, gate.caller());
        if (c == null || c.getStatus() == ConversationStatus.ERASED) {
            return AdminApi.problem(ProblemCode.NOT_FOUND, "Conversation not found", null, request);
        }
        return ResponseEntity.ok(store.messages(id, max).stream()
                .filter(m -> m.getRole() == MessageRole.USER || m.getRole() == MessageRole.ASSISTANT)
                .map(MessageDto::of).toList());
    }

    /**
     * Closes a conversation (no further turns); its history stays readable until retention purges it.
     *
     * @param id      conversation
     * @param request current request
     * @return 204 when closed
     */
    @PostMapping("/{id:[^:]+}:close")
    public ResponseEntity<?> close(@PathVariable UUID id, HttpServletRequest request) {
        var gate = api.authenticated(request);
        if (!gate.open()) {
            return gate.denied();
        }
        Conversation c = owned(id, gate.caller());
        if (c == null || c.getStatus() == ConversationStatus.ERASED) {
            return AdminApi.problem(ProblemCode.NOT_FOUND, "Conversation not found", null, request);
        }
        store.closeConversation(id);
        forgetMemory(c);
        return ResponseEntity.noContent().build();
    }

    /**
     * Erases a conversation for the caller: it disappears from their history and from what the model remembers.
     * Depending on {@code dynamic.ai.agent.conversations.erase-mode} the transcript is either deleted at once
     * ({@code HARD}) or kept for audit until the audit retention ends ({@code RETAIN_FOR_AUDIT}, default).
     * Irreversible for the caller.
     *
     * @param id      conversation
     * @param request current request
     * @return 204 when erased; 404 when unknown, not the caller's, or already erased
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> erase(@PathVariable UUID id, HttpServletRequest request) {
        var gate = api.authenticated(request);
        if (!gate.open()) {
            return gate.denied();
        }
        DaiPrincipal caller = gate.caller();
        Conversation c = owned(id, caller);
        boolean erased = c != null && (auditHold == null ? store.eraseConversation(id)
                : store.eraseConversationKeepingForAudit(id, auditHold));
        if (!erased) {
            return AdminApi.problem(ProblemCode.NOT_FOUND, "Conversation not found", null, request);
        }
        forgetMemory(c);
        audit.record(caller, AuditCategory.DATA_WRITE, AuditPlane.DATA, "CONVERSATION_ERASED", c.getWorkspaceId(),
                "conversation", id.toString(), null, null,
                Map.of("mode", auditHold == null ? "HARD" : "RETAIN_FOR_AUDIT"));
        return ResponseEntity.noContent().build();
    }

    /**
     * Deletes what the model remembers of a conversation that was closed or erased (OQ-45), best effort: the memory
     * expires by retention anyway, so a failure here is logged and never fails the request.
     */
    void forgetMemory(Conversation c) {
        UUID agent = c.getAgentResourceId();
        if (memory == null || agent == null) {
            return;
        }
        try {
            String key = ConversationKeys.memoryKey(c.getWorkspaceId(), agent, c.getPrincipalId(), c.getId());
            memory.delete(Sha256.of(key));
            memory.delete(Sha256.of(ConversationKeys.summaryKey(key)));
        } catch (RuntimeException e) {
            LoggerFactory.getLogger(ConversationController.class)
                    .warn("Could not delete chat memory of a closed conversation ({}); it expires by retention",
                            e.getClass().getSimpleName());
        }
    }

    private @Nullable Conversation owned(UUID id, DaiPrincipal caller) {
        return store.findConversationById(id).filter(c -> c.getPrincipalId().equals(caller.principalId()))
                .orElse(null);
    }
}
