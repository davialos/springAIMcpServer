package com.springaimcpservercommon.persistence.telemetry;

import com.springaimcpservercommon.persistence.support.Checks;
import com.springaimcpservercommon.persistence.support.PageRequest;
import com.springaimcpservercommon.persistence.support.Slice;
import com.springaimcpservercommon.persistence.support.SqlStates;
import com.springaimcpservercommon.persistence.support.StoreSupport;
import com.springaimcpservercommon.persistence.support.TimeRange;
import com.springaimcpservercommon.persistence.support.UtcTimes;
import com.springaimcpservercommon.persistence.unit.DaiStore;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Store for AI and MCP telemetry (migration V3): insert-only turn, model-call, tool-invocation and MCP-request
 * records; MCP session lifecycle; conversations and chat memory; read queries for dashboards and the audit UI.
 *
 * <p>Telemetry rows are written once, when the call completes (never updated). Reads over partitioned tables take a
 * {@link TimeRange} so PostgreSQL prunes partitions; lookups by turn or proposal id use the per-partition indexes.
 * All methods run in their own transaction (or join the caller's unit transaction).
 */
public final class TelemetryStore {

    /** Most turns {@link #turnsOfTrace} returns. */
    public static final int TRACE_LOOKUP_LIMIT = 20;

    /** Largest number of messages {@link #messages(UUID, int)} returns. */
    public static final int MAX_MESSAGES = 10_000;

    /** Largest batch for {@link #purgeExpiredConversations(int)}. */
    public static final int MAX_PURGE_BATCH = 10_000;

    private final StoreSupport db;
    private final Clock clock;

    /**
     * Creates the store.
     *
     * @param store the persistence unit
     * @param clock clock for activity and retention timestamps
     */
    public TelemetryStore(DaiStore store, Clock clock) {
        this.db = new StoreSupport(store);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ------------------------------------------------------------------------------------------------ insert-only

    /**
     * Records a completed agent turn.
     *
     * @param turn the turn
     * @return the persisted row
     */
    public AgentTurn recordTurn(NewAgentTurn turn) {
        AgentTurn entity = AgentTurn.of(turn);
        db.writeVoid(em -> em.persist(entity));
        return entity;
    }

    /**
     * Records a completed model call.
     *
     * @param call the call
     * @return the persisted row
     */
    public ModelCall recordModelCall(NewModelCall call) {
        ModelCall entity = ModelCall.of(call);
        db.writeVoid(em -> em.persist(entity));
        return entity;
    }

    /**
     * Records a completed tool invocation.
     *
     * @param invocation the invocation
     * @return the persisted row
     */
    public ToolInvocation recordToolInvocation(NewToolInvocation invocation) {
        ToolInvocation entity = ToolInvocation.of(invocation);
        db.writeVoid(em -> em.persist(entity));
        return entity;
    }

    /**
     * Records a completed MCP request.
     *
     * @param request the request
     * @return the persisted row
     */
    public McpRequest recordMcpRequest(NewMcpRequest request) {
        McpRequest entity = McpRequest.of(request);
        db.writeVoid(em -> em.persist(entity));
        return entity;
    }

    // ------------------------------------------------------------------------------------------------ MCP sessions

    /**
     * Opens an MCP session, or returns the existing one with the same session-id hash (idempotent under concurrent
     * opens on several nodes).
     *
     * @param session session data
     * @return the open (or existing) session
     * @throws IllegalStateException if a session with the same hash belongs to another principal
     */
    public McpSession openMcpSession(NewMcpSession session) {
        String hash = Checks.sha256(session.sessionIdHash(), "sessionIdHash");
        Optional<McpSession> existing = findMcpSession(hash);
        if (existing.isEmpty()) {
            McpSession created = McpSession.open(session, clock.instant());
            try {
                db.writeNew(em -> {
                    em.persist(created);
                    return created;
                });
                return created;
            } catch (RuntimeException e) {
                if (!SqlStates.isUniqueViolation(e)) {
                    throw e;
                }
                existing = findMcpSession(hash);
                if (existing.isEmpty()) {
                    throw e;
                }
            }
        }
        McpSession found = existing.get();
        if (!found.getPrincipalId().equals(session.principalId())) {
            throw new IllegalStateException("MCP session hash is bound to another principal");
        }
        return found;
    }

    /**
     * Finds a session by the hash of its {@code Mcp-Session-Id}.
     *
     * @param sessionIdHash {@code sha256:} hash
     * @return the session, if any
     */
    public Optional<McpSession> findMcpSession(String sessionIdHash) {
        return db.read(em -> em.createQuery(
                        "select s from McpSession s where s.sessionIdHash = :hash", McpSession.class)
                .setParameter("hash", sessionIdHash)
                .getResultStream()
                .findFirst());
    }

    /**
     * Advances {@code last_seen_at} of an open session. Uses a bulk update without version increment, so concurrent
     * requests of one session never cause optimistic-lock conflicts.
     *
     * @param sessionId session row id
     * @return {@code true} if an open session was touched
     */
    public boolean touchMcpSession(UUID sessionId) {
        Instant now = UtcTimes.now(clock);
        return db.write(em -> em.createQuery("update McpSession s set s.lastSeenAt = :now "
                        + "where s.id = :id and s.endedAt is null and s.lastSeenAt < :now")
                .setParameter("now", now)
                .setParameter("id", sessionId)
                .executeUpdate()) > 0;
    }

    /**
     * Ends an open session.
     *
     * @param sessionId session row id
     * @param reason    why it ended
     * @return {@code true} if the session was open and is now ended
     * @throws NoSuchElementException if the session does not exist
     */
    public boolean endMcpSession(UUID sessionId, McpSessionEndReason reason) {
        Instant now = clock.instant();
        return db.write(em -> {
            McpSession session = em.find(McpSession.class, sessionId);
            if (session == null) {
                throw new NoSuchElementException("MCP session " + sessionId + " not found");
            }
            return session.end(reason, now);
        });
    }

    // ------------------------------------------------------------------------------------------------ conversations

    /**
     * Returns the conversation with the given key hash, creating it when absent. Creation runs in its own
     * transaction; a concurrent creation on another node is detected by the unique key and resolved by re-reading.
     *
     * @param conversation conversation data
     * @return the existing or new conversation
     * @throws IllegalStateException if the key hash is bound to another principal
     */
    public Conversation openConversation(NewConversation conversation) {
        String hash = Checks.sha256(conversation.conversationKeyHash(), "conversationKeyHash");
        Optional<Conversation> existing = findConversation(hash);
        if (existing.isEmpty()) {
            Conversation created = Conversation.start(conversation, clock.instant());
            try {
                db.writeNew(em -> {
                    em.persist(created);
                    return created;
                });
                return created;
            } catch (RuntimeException e) {
                if (!SqlStates.isUniqueViolation(e)) {
                    throw e;
                }
                existing = findConversation(hash);
                if (existing.isEmpty()) {
                    throw e;
                }
            }
        }
        Conversation found = existing.get();
        if (!found.getPrincipalId().equals(conversation.principalId())) {
            throw new IllegalStateException("conversation key is bound to another principal");
        }
        return found;
    }

    /**
     * Finds a conversation by id.
     *
     * @param conversationId conversation id
     * @return the conversation, or empty
     */
    public Optional<Conversation> findConversationById(UUID conversationId) {
        return db.read(em -> Optional.ofNullable(em.find(Conversation.class, conversationId)));
    }

    /**
     * Finds a conversation by key hash.
     *
     * @param conversationKeyHash {@code sha256:} key hash
     * @return the conversation, if any
     */
    public Optional<Conversation> findConversation(String conversationKeyHash) {
        return db.read(em -> em.createQuery(
                        "select c from Conversation c where c.conversationKeyHash = :hash", Conversation.class)
                .setParameter("hash", conversationKeyHash)
                .getResultStream()
                .findFirst());
    }

    /**
     * Appends a message with the next sequence number. The conversation row is locked ({@code SELECT … FOR UPDATE})
     * so concurrent appends to one conversation are serialised and {@code seq} stays contiguous.
     *
     * @param conversationId conversation
     * @param message        message (already redacted)
     * @return the persisted message
     * @throws NoSuchElementException if the conversation does not exist
     * @throws IllegalStateException  if the conversation is closed or erased
     */
    public ConversationMessage appendMessage(UUID conversationId, NewMessage message) {
        Instant now = clock.instant();
        return db.write(em -> {
            Conversation conversation = lockConversation(em, conversationId);
            conversation.requireActive();
            Integer max = em.createQuery("select max(m.seq) from ConversationMessage m where m.conversationId = :id",
                            Integer.class)
                    .setParameter("id", conversationId)
                    .getSingleResult();
            ConversationMessage created = ConversationMessage.of(conversationId, max == null ? 0 : max + 1, message, now);
            em.persist(created);
            conversation.touch(now);
            return created;
        });
    }

    /**
     * Replaces all messages of a conversation (the {@code saveAll} semantics of Spring AI's
     * {@code ChatMemoryRepository}); sequence numbers restart at 0.
     *
     * @param conversationId conversation
     * @param messages       the complete new message list (already redacted)
     * @return the persisted messages in order
     * @throws NoSuchElementException if the conversation does not exist
     * @throws IllegalStateException  if the conversation is closed or erased
     */
    public List<ConversationMessage> replaceMessages(UUID conversationId, List<NewMessage> messages) {
        if (messages.size() > MAX_MESSAGES) {
            throw new IllegalArgumentException("at most " + MAX_MESSAGES + " messages per conversation");
        }
        Instant now = clock.instant();
        return db.write(em -> {
            Conversation conversation = lockConversation(em, conversationId);
            conversation.requireActive();
            deleteMessages(em, conversationId);
            List<ConversationMessage> created = new ArrayList<>(messages.size());
            for (int i = 0; i < messages.size(); i++) {
                ConversationMessage m = ConversationMessage.of(conversationId, i, messages.get(i), now);
                em.persist(m);
                created.add(m);
            }
            conversation.touch(now);
            return created;
        });
    }

    /**
     * Returns the most recent messages of a conversation in ascending sequence order.
     *
     * @param conversationId conversation
     * @param maxMessages    maximum number of (most recent) messages, 1–{@value #MAX_MESSAGES}
     * @return messages, oldest first
     */
    public List<ConversationMessage> messages(UUID conversationId, int maxMessages) {
        if (maxMessages < 1 || maxMessages > MAX_MESSAGES) {
            throw new IllegalArgumentException("maxMessages must be between 1 and " + MAX_MESSAGES);
        }
        List<ConversationMessage> newestFirst = db.read(em -> em.createQuery(
                        "select m from ConversationMessage m where m.conversationId = :id order by m.seq desc",
                        ConversationMessage.class)
                .setParameter("id", conversationId)
                .setMaxResults(maxMessages)
                .getResultList());
        List<ConversationMessage> ordered = new ArrayList<>(newestFirst);
        Collections.reverse(ordered);
        return List.copyOf(ordered);
    }

    /**
     * Conversations of a principal, most recently active first.
     *
     * @param principalId owner
     * @param page        page
     * @return one page
     */
    public Slice<Conversation> conversationsOf(UUID principalId, PageRequest page) {
        return db.read(em -> StoreSupport.slice(em.createQuery(
                        "select c from Conversation c where c.principalId = :p "
                                + "order by c.lastActivityAt desc, c.id desc", Conversation.class)
                .setParameter("p", principalId), page));
    }

    /**
     * Closes a conversation (no further messages).
     *
     * @param conversationId conversation
     * @throws NoSuchElementException if the conversation does not exist
     */
    public void closeConversation(UUID conversationId) {
        db.writeVoid(em -> lockConversation(em, conversationId).close());
    }

    /**
     * Erases a conversation on request: deletes every message and marks the row ERASED (the tombstone keeps no
     * content and is removed at retention).
     *
     * @param conversationId conversation
     * @return {@code true} if the conversation existed and was not already erased
     */
    public boolean eraseConversation(UUID conversationId) {
        Instant now = clock.instant();
        return db.write(em -> {
            Conversation conversation = em.find(Conversation.class, conversationId, LockModeType.PESSIMISTIC_WRITE);
            if (conversation == null || conversation.getStatus() == ConversationStatus.ERASED) {
                return false;
            }
            deleteMessages(em, conversationId);
            conversation.markErased(now);
            return true;
        });
    }

    /**
     * Deletes up to {@code batchSize} conversations whose retention has ended (messages go with them through the
     * {@code ON DELETE CASCADE} foreign key). Rows locked by concurrent work are skipped and picked up next time.
     *
     * @param batchSize maximum rows to delete (1–{@value #MAX_PURGE_BATCH})
     * @return number of conversations deleted
     */
    public int purgeExpiredConversations(int batchSize) {
        if (batchSize < 1 || batchSize > MAX_PURGE_BATCH) {
            throw new IllegalArgumentException("batchSize must be between 1 and " + MAX_PURGE_BATCH);
        }
        Instant now = UtcTimes.now(clock);
        String table = db.qualified("dai_conversation");
        return db.write(em -> em.createNativeQuery("DELETE FROM " + table + " WHERE id IN (SELECT id FROM " + table
                        + " WHERE retention_until < ?1 ORDER BY retention_until LIMIT ?2 FOR UPDATE SKIP LOCKED)")
                .setParameter(1, now)
                .setParameter(2, batchSize)
                .executeUpdate());
    }

    private Conversation lockConversation(EntityManager em, UUID conversationId) {
        Conversation conversation = em.find(Conversation.class, conversationId, LockModeType.PESSIMISTIC_WRITE);
        if (conversation == null) {
            throw new NoSuchElementException("conversation " + conversationId + " not found");
        }
        return conversation;
    }

    private void deleteMessages(EntityManager em, UUID conversationId) {
        // native: ConversationMessage is @Immutable and Hibernate refuses/warns on HQL mutation of immutable entities
        em.createNativeQuery("DELETE FROM " + db.qualified("dai_conversation_message") + " WHERE conversation_id = ?1")
                .setParameter(1, conversationId)
                .executeUpdate();
    }

    // ------------------------------------------------------------------------------------------------ read queries

    /**
     * Agent turns of a principal in a time range, newest first.
     *
     * @param principalId caller
     * @param range       time range on {@code started_at}
     * @param page        page
     * @return one page
     */
    public Slice<AgentTurn> turnsOfPrincipal(UUID principalId, TimeRange range, PageRequest page) {
        return db.read(em -> StoreSupport.slice(em.createQuery(
                        "select t from AgentTurn t where t.principalId = :p and t.startedAt >= :from and t.startedAt < :to "
                                + "order by t.startedAt desc, t.id desc", AgentTurn.class)
                .setParameter("p", principalId)
                .setParameter("from", range.from())
                .setParameter("to", range.to()), page));
    }

    /**
     * Agent turns of a workspace in a time range, newest first.
     *
     * @param workspaceId workspace
     * @param range       time range on {@code started_at}
     * @param page        page
     * @return one page
     */
    public Slice<AgentTurn> turnsOfWorkspace(UUID workspaceId, TimeRange range, PageRequest page) {
        return turnsOfWorkspace(workspaceId, range, TurnFilter.NONE, page);
    }

    /**
     * Agent turns of a workspace in a time range, newest first, optionally narrowed.
     *
     * @param workspaceId workspace
     * @param range       time window on {@code started_at}
     * @param filter      optional narrowing
     * @param page        page
     * @return the slice
     */
    public Slice<AgentTurn> turnsOfWorkspace(UUID workspaceId, TimeRange range, TurnFilter filter, PageRequest page) {
        StringBuilder jpql = new StringBuilder("select t from AgentTurn t where t.workspaceId = :w "
                + "and t.startedAt >= :from and t.startedAt < :to");
        if (filter.principalId() != null) {
            jpql.append(" and t.principalId = :principal");
        }
        if (filter.agentResourceId() != null) {
            jpql.append(" and t.agentResourceId = :agent");
        }
        if (filter.outcome() != null) {
            jpql.append(" and t.outcome = :outcome");
        }
        if (filter.channel() != null) {
            jpql.append(" and t.channel = :channel");
        }
        jpql.append(" order by t.startedAt desc, t.id desc");
        return db.read(em -> {
            var query = em.createQuery(jpql.toString(), AgentTurn.class)
                    .setParameter("w", workspaceId)
                    .setParameter("from", range.from())
                    .setParameter("to", range.to());
            if (filter.principalId() != null) {
                query.setParameter("principal", filter.principalId());
            }
            if (filter.agentResourceId() != null) {
                query.setParameter("agent", filter.agentResourceId());
            }
            if (filter.outcome() != null) {
                query.setParameter("outcome", filter.outcome());
            }
            if (filter.channel() != null) {
                query.setParameter("channel", filter.channel());
            }
            return StoreSupport.slice(query, page);
        });
    }

    /**
     * Turns that carry a trace correlation id, newest first (at most {@value #TRACE_LOOKUP_LIMIT}); this links an
     * OpenTelemetry trace back to the store.
     *
     * @param traceId trace id
     * @param range   time window on {@code started_at} (the table is partitioned by time)
     * @return matching turns
     */
    public List<AgentTurn> turnsOfTrace(String traceId, TimeRange range) {
        Checks.text(traceId, "traceId", 128);
        return db.read(em -> em.createQuery("select t from AgentTurn t where t.traceId = :trace "
                        + "and t.startedAt >= :from and t.startedAt < :to order by t.startedAt desc, t.id desc",
                        AgentTurn.class)
                .setParameter("trace", traceId)
                .setParameter("from", range.from())
                .setParameter("to", range.to())
                .setMaxResults(TRACE_LOOKUP_LIMIT)
                .getResultList());
    }

    /**
     * MCP requests that carry the given trace id, newest first (at most {@link #TRACE_LOOKUP_LIMIT}).
     *
     * @param traceId trace id
     * @param range   time window on {@code received_at} (the table is partitioned by time)
     * @return matching requests
     */
    public List<McpRequest> mcpRequestsOfTrace(String traceId, TimeRange range) {
        Checks.text(traceId, "traceId", 128);
        return db.read(em -> em.createQuery("select r from McpRequest r where r.traceId = :trace "
                        + "and r.receivedAt >= :from and r.receivedAt < :to order by r.receivedAt desc, r.id desc",
                        McpRequest.class)
                .setParameter("trace", traceId)
                .setParameter("from", range.from())
                .setParameter("to", range.to())
                .setMaxResults(TRACE_LOOKUP_LIMIT)
                .getResultList());
    }

    public Optional<AgentTurn> findTurn(UUID turnId, TimeRange range) {
        return db.read(em -> em.createQuery(
                        "select t from AgentTurn t where t.id = :id and t.startedAt >= :from and t.startedAt < :to",
                        AgentTurn.class)
                .setParameter("id", turnId)
                .setParameter("from", range.from())
                .setParameter("to", range.to())
                .getResultStream()
                .findFirst());
    }

    /**
     * Model calls of a turn in call order.
     *
     * @param turnId turn id
     * @return the calls
     */
    public List<ModelCall> modelCallsOfTurn(UUID turnId) {
        return db.read(em -> em.createQuery(
                        "select c from ModelCall c where c.turnId = :t order by c.startedAt, c.id", ModelCall.class)
                .setParameter("t", turnId)
                .setMaxResults(PageRequest.MAX_LIMIT)
                .getResultList());
    }

    /**
     * Model calls of a workspace in a time range, newest first.
     *
     * @param workspaceId workspace
     * @param range       time range on {@code started_at}
     * @param page        page
     * @return one page
     */
    public Slice<ModelCall> modelCallsOfWorkspace(UUID workspaceId, TimeRange range, PageRequest page) {
        return db.read(em -> StoreSupport.slice(em.createQuery(
                        "select c from ModelCall c where c.workspaceId = :w and c.startedAt >= :from and c.startedAt < :to "
                                + "order by c.startedAt desc, c.id desc", ModelCall.class)
                .setParameter("w", workspaceId)
                .setParameter("from", range.from())
                .setParameter("to", range.to()), page));
    }

    /**
     * Tool invocations of a turn in call order.
     *
     * @param turnId turn id
     * @return the invocations
     */
    public List<ToolInvocation> toolInvocationsOfTurn(UUID turnId) {
        return db.read(em -> em.createQuery(
                        "select i from ToolInvocation i where i.turnId = :t order by i.startedAt, i.id",
                        ToolInvocation.class)
                .setParameter("t", turnId)
                .setMaxResults(PageRequest.MAX_LIMIT)
                .getResultList());
    }

    /**
     * Tool invocations of an MCP request in call order.
     *
     * @param mcpRequestId MCP request id
     * @return the invocations
     */
    public List<ToolInvocation> toolInvocationsOfMcpRequest(UUID mcpRequestId) {
        return db.read(em -> em.createQuery(
                        "select i from ToolInvocation i where i.mcpRequestId = :r order by i.startedAt, i.id",
                        ToolInvocation.class)
                .setParameter("r", mcpRequestId)
                .setMaxResults(PageRequest.MAX_LIMIT)
                .getResultList());
    }

    /**
     * Tool invocations that created a change proposal.
     *
     * @param proposalId proposal id
     * @return the invocations (normally one)
     */
    public List<ToolInvocation> toolInvocationsOfProposal(UUID proposalId) {
        return db.read(em -> em.createQuery(
                        "select i from ToolInvocation i where i.proposalId = :p order by i.startedAt, i.id",
                        ToolInvocation.class)
                .setParameter("p", proposalId)
                .setMaxResults(PageRequest.MAX_LIMIT)
                .getResultList());
    }

    /**
     * Tool invocations run as a principal in a time range, newest first.
     *
     * @param principalId caller
     * @param range       time range on {@code started_at}
     * @param page        page
     * @return one page
     */
    public Slice<ToolInvocation> toolInvocationsOfPrincipal(UUID principalId, TimeRange range, PageRequest page) {
        return db.read(em -> StoreSupport.slice(em.createQuery(
                        "select i from ToolInvocation i where i.principalId = :p and i.startedAt >= :from "
                                + "and i.startedAt < :to order by i.startedAt desc, i.id desc", ToolInvocation.class)
                .setParameter("p", principalId)
                .setParameter("from", range.from())
                .setParameter("to", range.to()), page));
    }

    /**
     * Tool invocations of a workspace in a time range, newest first; optionally only write-guard violations.
     *
     * @param workspaceId     workspace
     * @param range           time range on {@code started_at}
     * @param violationsOnly  whether to return only invocations with {@code write_violation}
     * @param page            page
     * @return one page
     */
    public Slice<ToolInvocation> toolInvocationsOfWorkspace(UUID workspaceId, TimeRange range, boolean violationsOnly,
                                                            PageRequest page) {
        return toolInvocationsOfWorkspace(workspaceId, range, ToolInvocationFilter.violations(violationsOnly), page);
    }

    /**
     * Tool invocations of a workspace in a time range, newest first, optionally narrowed.
     *
     * @param workspaceId workspace
     * @param range       time window on {@code started_at}
     * @param filter      optional narrowing
     * @param page        page
     * @return the slice
     */
    public Slice<ToolInvocation> toolInvocationsOfWorkspace(UUID workspaceId, TimeRange range,
                                                            ToolInvocationFilter filter, PageRequest page) {
        StringBuilder jpql = new StringBuilder("select i from ToolInvocation i where i.workspaceId = :w "
                + "and i.startedAt >= :from and i.startedAt < :to");
        if (filter.toolName() != null) {
            jpql.append(" and i.toolName = :tool");
        }
        if (filter.status() != null) {
            jpql.append(" and i.status = :status");
        }
        if (filter.principalId() != null) {
            jpql.append(" and i.principalId = :principal");
        }
        if (filter.violationsOnly()) {
            jpql.append(" and i.writeViolation = true");
        }
        if (filter.problemsOnly()) {
            jpql.append(" and i.status <> :ok and i.status <> :empty");
        }
        jpql.append(" order by i.startedAt desc, i.id desc");
        return db.read(em -> {
            var query = em.createQuery(jpql.toString(), ToolInvocation.class)
                    .setParameter("w", workspaceId)
                    .setParameter("from", range.from())
                    .setParameter("to", range.to());
            if (filter.toolName() != null) {
                query.setParameter("tool", filter.toolName());
            }
            if (filter.status() != null) {
                query.setParameter("status", filter.status());
            }
            if (filter.principalId() != null) {
                query.setParameter("principal", filter.principalId());
            }
            if (filter.problemsOnly()) {
                query.setParameter("ok", ToolInvocationStatus.OK).setParameter("empty", ToolInvocationStatus.EMPTY);
            }
            return StoreSupport.slice(query, page);
        });
    }

    /**
     * Per-tool aggregates of a workspace in a time window, most used first.
     *
     * @param workspaceId workspace
     * @param range       time window on {@code started_at}
     * @param limit       most tools returned (1..{@value PageRequest#MAX_LIMIT})
     * @return one row per tool
     */
    public List<ToolStat> toolStats(UUID workspaceId, TimeRange range, int limit) {
        if (limit < 1 || limit > PageRequest.MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + PageRequest.MAX_LIMIT);
        }
        String sql = "SELECT tool_name, count(*), "
                + "count(*) FILTER (WHERE status IN ('ERROR', 'TIMEOUT', 'UNAVAILABLE')), "
                + "count(*) FILTER (WHERE status = 'NOT_PERMITTED'), "
                + "count(*) FILTER (WHERE write_violation), "
                + "avg(extract(epoch FROM (ended_at - started_at)) * 1000), "
                + "max(extract(epoch FROM (ended_at - started_at)) * 1000) "
                + "FROM " + db.qualified("dai_tool_invocation")
                + " WHERE workspace_id = ?1 AND started_at >= ?2 AND started_at < ?3 "
                + "GROUP BY tool_name ORDER BY count(*) DESC, tool_name LIMIT ?4";
        return db.read(em -> {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(sql)
                    .setParameter(1, workspaceId)
                    .setParameter(2, range.from())
                    .setParameter(3, range.to())
                    .setParameter(4, limit)
                    .getResultList();
            List<ToolStat> stats = new ArrayList<>(rows.size());
            for (Object[] row : rows) {
                stats.add(new ToolStat((String) row[0], ((Number) row[1]).longValue(),
                        ((Number) row[2]).longValue(), ((Number) row[3]).longValue(), ((Number) row[4]).longValue(),
                        ((Number) row[5]).doubleValue(), ((Number) row[6]).doubleValue()));
            }
            return stats;
        });
    }

    /**
     * MCP requests of a workspace in a time range, newest first, optionally narrowed.
     *
     * @param workspaceId workspace
     * @param range       time window on {@code received_at}
     * @param filter      optional narrowing
     * @param page        page
     * @return the slice
     */
    public Slice<McpRequest> mcpRequestsOfWorkspace(UUID workspaceId, TimeRange range, McpRequestFilter filter,
                                                    PageRequest page) {
        StringBuilder jpql = new StringBuilder("select r from McpRequest r where r.workspaceId = :w "
                + "and r.receivedAt >= :from and r.receivedAt < :to");
        if (filter.method() != null) {
            jpql.append(" and r.jsonrpcMethod = :method");
        }
        if (filter.toolName() != null) {
            jpql.append(" and r.toolName = :tool");
        }
        if (filter.status() != null) {
            jpql.append(" and r.status = :status");
        }
        if (filter.principalId() != null) {
            jpql.append(" and r.principalId = :principal");
        }
        jpql.append(" order by r.receivedAt desc, r.id desc");
        return db.read(em -> {
            var query = em.createQuery(jpql.toString(), McpRequest.class)
                    .setParameter("w", workspaceId)
                    .setParameter("from", range.from())
                    .setParameter("to", range.to());
            if (filter.method() != null) {
                query.setParameter("method", filter.method());
            }
            if (filter.toolName() != null) {
                query.setParameter("tool", filter.toolName());
            }
            if (filter.status() != null) {
                query.setParameter("status", filter.status());
            }
            if (filter.principalId() != null) {
                query.setParameter("principal", filter.principalId());
            }
            return StoreSupport.slice(query, page);
        });
    }

    /**
     * One MCP request.
     *
     * @param requestId request id
     * @param range     time window on {@code received_at} (the table is partitioned by time)
     * @return the request, if it is in the window
     */
    public Optional<McpRequest> findMcpRequest(UUID requestId, TimeRange range) {
        return db.read(em -> em.createQuery("select r from McpRequest r where r.id = :id "
                        + "and r.receivedAt >= :from and r.receivedAt < :to", McpRequest.class)
                .setParameter("id", requestId)
                .setParameter("from", range.from())
                .setParameter("to", range.to())
                .getResultStream()
                .findFirst());
    }

    public Slice<McpRequest> mcpRequestsOfSession(UUID mcpSessionId, TimeRange range, PageRequest page) {
        return db.read(em -> StoreSupport.slice(em.createQuery(
                        "select r from McpRequest r where r.mcpSessionId = :s and r.receivedAt >= :from "
                                + "and r.receivedAt < :to order by r.receivedAt, r.id", McpRequest.class)
                .setParameter("s", mcpSessionId)
                .setParameter("from", range.from())
                .setParameter("to", range.to()), page));
    }

    /**
     * MCP requests of a principal in a time range, newest first.
     *
     * @param principalId caller
     * @param range       time range on {@code received_at}
     * @param page        page
     * @return one page
     */
    public Slice<McpRequest> mcpRequestsOfPrincipal(UUID principalId, TimeRange range, PageRequest page) {
        return db.read(em -> StoreSupport.slice(em.createQuery(
                        "select r from McpRequest r where r.principalId = :p and r.receivedAt >= :from "
                                + "and r.receivedAt < :to order by r.receivedAt desc, r.id desc", McpRequest.class)
                .setParameter("p", principalId)
                .setParameter("from", range.from())
                .setParameter("to", range.to()), page));
    }

    /**
     * MCP sessions of a principal, newest first.
     *
     * @param principalId caller
     * @param page        page
     * @return one page
     */
    public Slice<McpSession> mcpSessionsOfPrincipal(UUID principalId, PageRequest page) {
        return db.read(em -> StoreSupport.slice(em.createQuery(
                        "select s from McpSession s where s.principalId = :p order by s.startedAt desc, s.id desc",
                        McpSession.class)
                .setParameter("p", principalId), page));
    }
}
