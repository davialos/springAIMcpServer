package com.springaimcpservercommon.persistence.audit;

import com.springaimcpservercommon.core.id.Ids;
import com.springaimcpservercommon.persistence.support.Checks;
import com.springaimcpservercommon.persistence.support.PageRequest;
import com.springaimcpservercommon.persistence.support.Slice;
import com.springaimcpservercommon.persistence.support.StoreSupport;
import com.springaimcpservercommon.persistence.support.TimeRange;
import com.springaimcpservercommon.persistence.support.UtcTimes;
import com.springaimcpservercommon.persistence.unit.DaiStore;
import jakarta.persistence.EntityManager;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Tamper-evident audit trail over {@code dai_audit_chain} / {@code dai_audit_event} (LLD-10 §4, ADR-0018).
 *
 * <h2>Append protocol</h2>
 * Each append runs in its own transaction (REQUIRES_NEW), so the audit record commits even when the audited work
 * rolls back:
 * <ol>
 *   <li>chain id = workspace id text, or {@value #SYSTEM_CHAIN} without a workspace;</li>
 *   <li>{@code SELECT last_seq, last_hash … FOR UPDATE} on the chain head; if the head does not exist it is created
 *       with {@code INSERT … ON CONFLICT DO NOTHING} (last_seq 0, last_hash = genesis {@code Sha256.of(chainId)})
 *       and locked again — so concurrent appends to one chain are serialised while other chains never contend;</li>
 *   <li>{@code chain_seq = last_seq + 1}, {@code prev_hash = last_hash}, {@code hash} per
 *       {@link AuditCanonicalForm};</li>
 *   <li>insert the event, advance the head.</li>
 * </ol>
 * {@link #verify(String, long, long)} recomputes hashes and links in sequence order and reports the first broken
 * sequence number (tampered content, forged or missing rows, duplicated sequence numbers).
 */
public final class AuditTrail {

    /** Chain of events without a workspace. */
    public static final String SYSTEM_CHAIN = "system";

    /** Pattern of chain ids ({@code ck_audit_chain_id}). */
    public static final Pattern CHAIN_ID = Pattern.compile("^[a-z0-9-]{3,64}$");

    /** Largest range one {@link #verify} call may cover. */
    public static final long MAX_VERIFY_SPAN = 1_000_000;

    private static final int VERIFY_PAGE = 500;

    private final StoreSupport db;
    private final String environmentId;
    private final Clock clock;

    /**
     * Creates the trail.
     *
     * @param store         the persistence unit
     * @param environmentId environment id recorded in every event (the store's {@code dai_environment} id)
     * @param clock         clock for event times
     */
    public AuditTrail(DaiStore store, String environmentId, Clock clock) {
        this.db = new StoreSupport(store);
        this.environmentId = Checks.text(environmentId, "environmentId", 128);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Chain id for a workspace.
     *
     * @param workspaceId workspace or {@code null}
     * @return the workspace id text, or {@value #SYSTEM_CHAIN}
     */
    public static String chainIdFor(@Nullable UUID workspaceId) {
        return workspaceId == null ? SYSTEM_CHAIN : workspaceId.toString();
    }

    /**
     * Appends an event in a new transaction (see the class description).
     *
     * @param draft the event
     * @return id, chain position and hash of the stored event
     */
    public AppendedAuditEvent append(AuditEventDraft draft) {
        Objects.requireNonNull(draft, "draft");
        String chainId = chainIdFor(draft.workspaceId());
        Instant requested = draft.occurredAt();
        Instant occurredAt = UtcTimes.micros(requested != null ? requested : clock.instant());
        return db.writeNew(em -> {
            Object[] head = lockHead(em, chainId);
            if (head == null) {
                em.createNativeQuery("INSERT INTO " + db.qualified("dai_audit_chain")
                                + " (chain_id, last_seq, last_hash) VALUES (?1, 0, ?2) ON CONFLICT (chain_id) DO NOTHING")
                        .setParameter(1, chainId)
                        .setParameter(2, AuditCanonicalForm.genesis(chainId))
                        .executeUpdate();
                head = lockHead(em, chainId);
                if (head == null) {
                    throw new IllegalStateException("audit chain head " + chainId + " could not be created");
                }
            }
            long seq = ((Number) head[0]).longValue() + 1;
            String prevHash = String.valueOf(head[1]);
            AuditEvent event = AuditEvent.chained(draft, Ids.newId(occurredAt.toEpochMilli()), occurredAt, chainId, seq,
                    environmentId, prevHash);
            em.persist(event);
            em.createNativeQuery("UPDATE " + db.qualified("dai_audit_chain")
                            + " SET last_seq = ?1, last_hash = ?2, last_event_at = ?3 WHERE chain_id = ?4")
                    .setParameter(1, seq)
                    .setParameter(2, event.getHash())
                    .setParameter(3, occurredAt)
                    .setParameter(4, chainId)
                    .executeUpdate();
            return new AppendedAuditEvent(event.getId(), chainId, seq, occurredAt, event.getHash());
        });
    }

    private Object @Nullable [] lockHead(EntityManager em, String chainId) {
        List<?> rows = em.createNativeQuery("SELECT last_seq, last_hash FROM " + db.qualified("dai_audit_chain")
                        + " WHERE chain_id = ?1 FOR UPDATE")
                .setParameter(1, chainId)
                .getResultList();
        return rows.isEmpty() ? null : (Object[]) rows.getFirst();
    }

    /**
     * Current head of a chain.
     *
     * @param chainId chain id
     * @return the head, if the chain exists
     */
    public Optional<AuditChain> head(String chainId) {
        return db.read(em -> Optional.ofNullable(em.find(AuditChain.class, chainId)));
    }

    /**
     * Verifies events {@code fromSeq..toSeq} of a chain: contiguous sequence numbers, each {@code prev_hash} equal to
     * the previous event's hash (genesis for sequence 1) and each stored hash equal to the recomputed one. Events
     * beyond the chain head are checked too (a row inserted behind the application's back is reported).
     *
     * @param chainId chain id
     * @param fromSeq first sequence number (≥ 1)
     * @param toSeq   last sequence number (≥ fromSeq, span ≤ {@value #MAX_VERIFY_SPAN})
     * @return the verification result
     */
    public ChainVerification verify(String chainId, long fromSeq, long toSeq) {
        Checks.matches(chainId, CHAIN_ID, "chainId");
        if (fromSeq < 1 || toSeq < fromSeq || toSeq - fromSeq >= MAX_VERIFY_SPAN) {
            throw new IllegalArgumentException("invalid sequence range " + fromSeq + ".." + toSeq);
        }
        return db.read(em -> {
            String expectedPrev;
            boolean anchored = true;
            if (fromSeq == 1) {
                expectedPrev = AuditCanonicalForm.genesis(chainId);
            } else {
                List<AuditEvent> before = page(em, chainId, fromSeq - 1, fromSeq - 1);
                expectedPrev = before.size() == 1 ? before.getFirst().getHash() : null;
                anchored = expectedPrev != null;
            }
            AuditChain head = em.find(AuditChain.class, chainId);
            long headSeq = head == null ? 0 : head.getLastSeq();
            long expectedSeq = fromSeq;
            long checked = 0;
            while (expectedSeq <= toSeq) {
                List<AuditEvent> events = page(em, chainId, expectedSeq, toSeq);
                if (events.isEmpty()) {
                    break;
                }
                for (AuditEvent e : events) {
                    if (e.getChainSeq() < expectedSeq) {
                        return broken(chainId, fromSeq, toSeq, checked, e.getChainSeq(), "duplicate sequence number",
                                anchored);
                    }
                    if (e.getChainSeq() > expectedSeq) {
                        return broken(chainId, fromSeq, toSeq, checked, expectedSeq, "missing event", anchored);
                    }
                    if (expectedPrev != null && !expectedPrev.equals(e.getPrevHash())) {
                        return broken(chainId, fromSeq, toSeq, checked, expectedSeq, "prev_hash does not link",
                                anchored);
                    }
                    if (!AuditCanonicalForm.hash(e).equals(e.getHash())) {
                        return broken(chainId, fromSeq, toSeq, checked, expectedSeq, "hash mismatch", anchored);
                    }
                    expectedPrev = e.getHash();
                    expectedSeq++;
                    checked++;
                }
                em.clear(); // keep the persistence context bounded while walking long chains
            }
            if (expectedSeq <= Math.min(toSeq, headSeq)) {
                return broken(chainId, fromSeq, toSeq, checked, expectedSeq, "missing event", anchored);
            }
            long lastChecked = expectedSeq - 1;
            if (lastChecked > headSeq) {
                return broken(chainId, fromSeq, toSeq, checked, headSeq + 1, "event beyond chain head", anchored);
            }
            if (head != null && checked > 0 && lastChecked == headSeq && !head.getLastHash().equals(expectedPrev)) {
                return broken(chainId, fromSeq, toSeq, checked, headSeq, "chain head hash does not match", anchored);
            }
            return new ChainVerification(chainId, fromSeq, toSeq, checked, null, null, anchored);
        });
    }

    private static ChainVerification broken(String chainId, long fromSeq, long toSeq, long checked, long seq,
                                            String problem, boolean anchored) {
        return new ChainVerification(chainId, fromSeq, toSeq, checked, seq, problem, anchored);
    }

    private static List<AuditEvent> page(EntityManager em, String chainId, long from, long to) {
        return em.createQuery("select e from AuditEvent e where e.chainId = :chain and e.chainSeq >= :from "
                        + "and e.chainSeq <= :to order by e.chainSeq, e.id", AuditEvent.class)
                .setParameter("chain", chainId)
                .setParameter("from", from)
                .setParameter("to", to)
                .setMaxResults(VERIFY_PAGE)
                .getResultList();
    }

    // ---------------------------------------------------------------------------------------------- read queries

    /**
     * Events of a workspace in a time range, newest first.
     *
     * @param workspaceId workspace
     * @param range       range on {@code occurred_at}
     * @param page        page
     * @return one page
     */
    public Slice<AuditEvent> eventsOfWorkspace(UUID workspaceId, TimeRange range, PageRequest page) {
        return db.read(em -> StoreSupport.slice(em.createQuery(
                        "select e from AuditEvent e where e.workspaceId = :w and e.occurredAt >= :from "
                                + "and e.occurredAt < :to order by e.occurredAt desc, e.id desc", AuditEvent.class)
                .setParameter("w", workspaceId)
                .setParameter("from", range.from())
                .setParameter("to", range.to()), page));
    }

    /**
     * Events by an actor in a time range, newest first.
     *
     * @param actorId acting principal
     * @param range   range on {@code occurred_at}
     * @param page    page
     * @return one page
     */
    public Slice<AuditEvent> eventsOfActor(UUID actorId, TimeRange range, PageRequest page) {
        return db.read(em -> StoreSupport.slice(em.createQuery(
                        "select e from AuditEvent e where e.actorId = :a and e.occurredAt >= :from "
                                + "and e.occurredAt < :to order by e.occurredAt desc, e.id desc", AuditEvent.class)
                .setParameter("a", actorId)
                .setParameter("from", range.from())
                .setParameter("to", range.to()), page));
    }

    /**
     * Denied decisions in a time range, newest first.
     *
     * @param range range on {@code occurred_at}
     * @param page  page
     * @return one page
     */
    public Slice<AuditEvent> denials(TimeRange range, PageRequest page) {
        return db.read(em -> StoreSupport.slice(em.createQuery(
                        "select e from AuditEvent e where e.decision = :d and e.occurredAt >= :from "
                                + "and e.occurredAt < :to order by e.occurredAt desc, e.id desc", AuditEvent.class)
                .setParameter("d", AuditDecision.DENY)
                .setParameter("from", range.from())
                .setParameter("to", range.to()), page));
    }

    /**
     * Decision trail of a change proposal in time order.
     *
     * @param proposalId proposal id
     * @return events
     */
    public List<AuditEvent> eventsOfProposal(UUID proposalId) {
        return db.read(em -> em.createQuery("select e from AuditEvent e where e.proposalId = :p "
                        + "order by e.occurredAt, e.id", AuditEvent.class)
                .setParameter("p", proposalId)
                .setMaxResults(PageRequest.MAX_LIMIT)
                .getResultList());
    }

    /**
     * Events of an agent turn in time order.
     *
     * @param turnId turn id
     * @return events
     */
    public List<AuditEvent> eventsOfTurn(UUID turnId) {
        return db.read(em -> em.createQuery("select e from AuditEvent e where e.turnId = :t "
                        + "order by e.occurredAt, e.id", AuditEvent.class)
                .setParameter("t", turnId)
                .setMaxResults(PageRequest.MAX_LIMIT)
                .getResultList());
    }
}
