package com.springaimcpservercommon.persistence.proposal;

import com.springaimcpservercommon.persistence.proposal.ProposalRuleViolationException.Reason;
import com.springaimcpservercommon.persistence.support.PageRequest;
import com.springaimcpservercommon.persistence.support.Slice;
import com.springaimcpservercommon.persistence.support.SqlStates;
import com.springaimcpservercommon.persistence.support.StoreSupport;
import com.springaimcpservercommon.persistence.support.UtcTimes;
import com.springaimcpservercommon.persistence.unit.DaiStore;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Store of {@link ChangeProposal} aggregates (migration V4, LLD-11).
 *
 * <ul>
 *   <li>{@link #create} is idempotent per (owner, idempotency key): the same key with the same content hash returns
 *       the existing proposal; with different content it fails with {@code IDEMPOTENCY_KEY_REUSED}.</li>
 *   <li>{@link #transition} loads the aggregate with a row lock ({@code SELECT … FOR UPDATE}), checks the caller's
 *       expected {@code row_version} (If-Match), applies the change and flushes; concurrent changes are serialised
 *       and a stale expectation fails with {@code STALE_VERSION}. When a transition ends in a terminal state,
 *       retention is extended to {@code now + terminalRetention} (LLD-11 §9 default 7 days).</li>
 *   <li>Sweeps: {@link #expireDue}, {@link #applyingSince} (crash reconciliation, LLD-11 §10) and
 *       {@link #purgeRetained} process batches with {@code FOR UPDATE SKIP LOCKED}, so several nodes can run them.</li>
 * </ul>
 * Aggregates returned by this store are detached with all collections initialised; change them only through
 * {@link #transition}.
 */
public final class ChangeProposalStore {

    /** Largest batch for sweeps. */
    public static final int MAX_BATCH = 1000;

    private final StoreSupport db;
    private final Clock clock;
    private final Duration terminalRetention;

    /**
     * Creates the store.
     *
     * @param store             the persistence unit
     * @param clock             clock for transitions and sweeps
     * @param terminalRetention how long proposals are kept after reaching a terminal state
     *                          ({@code dynamic.ai.agent.write.retention}, default 7 days)
     */
    public ChangeProposalStore(DaiStore store, Clock clock, Duration terminalRetention) {
        this.db = new StoreSupport(store);
        this.clock = Objects.requireNonNull(clock, "clock");
        if (terminalRetention.isNegative()) {
            throw new IllegalArgumentException("terminalRetention must not be negative");
        }
        this.terminalRetention = terminalRetention;
    }

    // ---------------------------------------------------------------------------------------------- create / read

    /**
     * Creates a proposal, honouring the owner's idempotency key.
     *
     * @param data proposal data
     * @return the new proposal, or the existing one for a repeated idempotency key with the same content hash
     * @throws ProposalRuleViolationException with {@code IDEMPOTENCY_KEY_REUSED} if the key was used for other content
     */
    public ChangeProposal create(NewChangeProposal data) {
        String key = data.idempotencyKey();
        if (key != null) {
            Optional<ChangeProposal> existing = findByIdempotencyKey(data.ownerId(), key);
            if (existing.isPresent()) {
                return sameContent(existing.get(), data);
            }
        }
        ChangeProposal proposal = ChangeProposal.propose(data, clock.instant());
        if (key == null) {
            db.writeVoid(em -> em.persist(proposal));
            return proposal;
        }
        try {
            db.writeNew(em -> {
                em.persist(proposal);
                return proposal;
            });
            return proposal;
        } catch (RuntimeException e) {
            if (!SqlStates.isUniqueViolation(e)) {
                throw e;
            }
            ChangeProposal winner = findByIdempotencyKey(data.ownerId(), key).orElseThrow(() -> e);
            return sameContent(winner, data);
        }
    }

    /**
     * The key identifies the creation request, so a retry matches if it carries the content hash the proposal was
     * created with (the current hash may differ after owner edits).
     */
    private static ChangeProposal sameContent(ChangeProposal existing, NewChangeProposal data) {
        String requested = data.contentHash();
        if (requested.equals(existing.getContentHash()) || requested.equals(existing.getInitialContentHash())) {
            return existing;
        }
        throw new ProposalRuleViolationException(Reason.IDEMPOTENCY_KEY_REUSED,
                "idempotency key already used for other content");
    }

    /**
     * Loads a proposal by id (for approvers and auditors; callers authorise access).
     *
     * @param id proposal id
     * @return the proposal, if found
     */
    public Optional<ChangeProposal> find(UUID id) {
        return db.read(em -> Optional.ofNullable(em.find(ChangeProposal.class, id)).map(ChangeProposalStore::initialized));
    }

    /**
     * Loads a proposal only if it belongs to the given owner (a guessed id alone never reveals a proposal, LLD-11 §9).
     *
     * @param id      proposal id
     * @param ownerId expected owner
     * @return the proposal, if found and owned by {@code ownerId}
     */
    public Optional<ChangeProposal> findForOwner(UUID id, UUID ownerId) {
        return find(id).filter(p -> p.getOwnerId().equals(ownerId));
    }

    private Optional<ChangeProposal> findByIdempotencyKey(UUID ownerId, String key) {
        return db.read(em -> em.createQuery("select p from ChangeProposal p "
                        + "where p.ownerId = :owner and p.idempotencyKey = :key", ChangeProposal.class)
                .setParameter("owner", ownerId)
                .setParameter("key", key)
                .getResultStream()
                .findFirst()
                .map(ChangeProposalStore::initialized));
    }

    /**
     * Pending proposals (PROPOSED, EDITED, AWAITING_APPROVAL) of an owner, newest first.
     *
     * @param ownerId owner
     * @param page    page
     * @return one page of summaries
     */
    public Slice<ProposalSummary> pendingOf(UUID ownerId, PageRequest page) {
        Slice<ChangeProposal> slice = db.read(em -> StoreSupport.slice(em.createQuery(
                        "select p from ChangeProposal p where p.ownerId = :owner and p.state in :states "
                                + "order by p.createdAt desc, p.id desc", ChangeProposal.class)
                .setParameter("owner", ownerId)
                .setParameter("states", List.of(ProposalState.PROPOSED, ProposalState.EDITED,
                        ProposalState.AWAITING_APPROVAL)), page));
        return summaries(slice);
    }

    /**
     * Approval inbox: unexpired proposals AWAITING_APPROVAL in the given workspaces that the approver does not own
     * and has not decided yet, oldest first. The caller passes the workspaces where the principal holds the approver
     * role.
     *
     * @param approverId   approving principal
     * @param workspaceIds workspaces to include
     * @param page         page
     * @return one page of summaries
     */
    public Slice<ProposalSummary> approvalInbox(UUID approverId, Collection<UUID> workspaceIds, PageRequest page) {
        if (workspaceIds.isEmpty()) {
            return new Slice<>(List.of(), page, false);
        }
        Instant now = UtcTimes.now(clock);
        List<UUID> workspaces = List.copyOf(workspaceIds);
        Slice<ChangeProposal> slice = db.read(em -> StoreSupport.slice(em.createQuery(
                        "select p from ChangeProposal p where p.state = :state and p.workspaceId in :ws "
                                + "and p.ownerId <> :me and p.expiresAt > :now and not exists ("
                                + "select a from ChangeProposalApproval a where a.proposal = p and a.id.approverId = :me) "
                                + "order by p.createdAt, p.id", ChangeProposal.class)
                .setParameter("state", ProposalState.AWAITING_APPROVAL)
                .setParameter("ws", workspaces)
                .setParameter("me", approverId)
                .setParameter("now", now), page));
        return summaries(slice);
    }

    /**
     * Pending proposals of a workspace that expire within the given window (for reminders), soonest first.
     *
     * @param workspaceId workspace
     * @param within      window from now
     * @param page        page
     * @return one page of summaries
     */
    public Slice<ProposalSummary> expiringWithin(UUID workspaceId, Duration within, PageRequest page) {
        Instant now = UtcTimes.now(clock);
        Instant until = now.plus(within);
        Slice<ChangeProposal> slice = db.read(em -> StoreSupport.slice(em.createQuery(
                        "select p from ChangeProposal p where p.workspaceId = :ws and p.state in :states "
                                + "and p.expiresAt > :now and p.expiresAt <= :until order by p.expiresAt, p.id",
                        ChangeProposal.class)
                .setParameter("ws", workspaceId)
                .setParameter("states", List.of(ProposalState.PROPOSED, ProposalState.EDITED,
                        ProposalState.AWAITING_APPROVAL))
                .setParameter("now", now)
                .setParameter("until", until), page));
        return summaries(slice);
    }

    // ---------------------------------------------------------------------------------------------- transitions

    /**
     * Applies a change to a proposal under a row lock with optimistic version check.
     *
     * @param id              proposal id
     * @param expectedVersion the {@code row_version} the caller saw (If-Match), or {@code null} to skip the check
     * @param change          the transition, receiving the managed aggregate and the current time
     * @return the changed proposal (detached, collections initialised, new version)
     * @throws NoSuchElementException          if the proposal does not exist
     * @throws ProposalRuleViolationException  if the transition is illegal or the version is stale
     */
    public ChangeProposal transition(UUID id, @Nullable Long expectedVersion, Transition change) {
        Instant now = clock.instant();
        try {
            return db.write(em -> {
                ChangeProposal proposal = em.find(ChangeProposal.class, id, LockModeType.PESSIMISTIC_WRITE);
                if (proposal == null) {
                    throw new NoSuchElementException("proposal " + id + " not found");
                }
                if (expectedVersion != null && proposal.getRowVersion() != expectedVersion) {
                    throw new ProposalRuleViolationException(Reason.STALE_VERSION,
                            "proposal " + id + " is at version " + proposal.getRowVersion() + ", not " + expectedVersion);
                }
                change.apply(proposal, now);
                if (proposal.getState().isTerminal()) {
                    proposal.retainUntil(now.plus(terminalRetention));
                }
                em.flush();
                return initialized(proposal);
            });
        } catch (OptimisticLockingFailureException | OptimisticLockException e) {
            throw new ProposalRuleViolationException(Reason.STALE_VERSION, "proposal " + id + " changed concurrently", e);
        }
    }

    /**
     * Owner edit (see {@link ChangeProposal#edit}).
     *
     * @param id              proposal id
     * @param expectedVersion If-Match version, or {@code null}
     * @param actorId         owner
     * @param edits           record edits
     * @param newContentHash  new content hash
     * @param validationJson  new validation report, if any
     * @return the changed proposal
     */
    public ChangeProposal edit(UUID id, @Nullable Long expectedVersion, UUID actorId, List<RecordEdit> edits,
                               String newContentHash, @Nullable String validationJson) {
        return transition(id, expectedVersion, (p, now) -> p.edit(actorId, edits, newContentHash, validationJson, now));
    }

    /**
     * Owner confirmation (see {@link ChangeProposal#confirm}).
     *
     * @param id              proposal id
     * @param expectedVersion If-Match version, or {@code null}
     * @param actorId         owner
     * @param contentHash     hash the user saw
     * @return the changed proposal
     */
    public ChangeProposal confirm(UUID id, @Nullable Long expectedVersion, UUID actorId, String contentHash) {
        return transition(id, expectedVersion, (p, now) -> p.confirm(actorId, contentHash, now));
    }

    /**
     * Second-person approval (see {@link ChangeProposal#approve}).
     *
     * @param id         proposal id
     * @param approverId approver (not the owner)
     * @param comment    optional comment
     * @return the changed proposal
     */
    public ChangeProposal approve(UUID id, UUID approverId, @Nullable String comment) {
        return transition(id, null, (p, now) -> p.approve(approverId, comment, now));
    }

    /**
     * Second-person rejection (see {@link ChangeProposal#reject}).
     *
     * @param id         proposal id
     * @param approverId approver (not the owner)
     * @param comment    mandatory comment
     * @return the changed proposal
     */
    public ChangeProposal reject(UUID id, UUID approverId, String comment) {
        return transition(id, null, (p, now) -> p.reject(approverId, comment, now));
    }

    /**
     * Owner withdrawal (see {@link ChangeProposal#decline}).
     *
     * @param id      proposal id
     * @param actorId owner
     * @param reason  optional reason code
     * @return the changed proposal
     */
    public ChangeProposal decline(UUID id, UUID actorId, @Nullable String reason) {
        return transition(id, null, (p, now) -> p.decline(actorId, reason, now));
    }

    /**
     * CONFIRMED → APPLYING; call in its own transaction before invoking the host write path.
     *
     * @param id proposal id
     * @return the changed proposal
     */
    public ChangeProposal markApplying(UUID id) {
        return transition(id, null, (p, now) -> p.markApplying(now));
    }

    /**
     * APPLYING → APPLIED.
     *
     * @param id              proposal id
     * @param hostRevisionRef host revision created by the write, if known
     * @return the changed proposal
     */
    public ChangeProposal markApplied(UUID id, @Nullable String hostRevisionRef) {
        return transition(id, null, (p, now) -> p.markApplied(hostRevisionRef, now));
    }

    /**
     * CONFIRMED/APPLYING → CONFLICT.
     *
     * @param id      proposal id
     * @param code    failure code
     * @param message sanitised message, if any
     * @return the changed proposal
     */
    public ChangeProposal markConflict(UUID id, String code, @Nullable String message) {
        return transition(id, null, (p, now) -> p.markConflict(code, message, now));
    }

    /**
     * CONFIRMED/APPLYING → FAILED.
     *
     * @param id      proposal id
     * @param code    failure code
     * @param message sanitised message, if any
     * @return the changed proposal
     */
    public ChangeProposal markFailed(UUID id, String code, @Nullable String message) {
        return transition(id, null, (p, now) -> p.markFailed(code, message, now));
    }

    // ---------------------------------------------------------------------------------------------- sweeps

    /**
     * Expires up to {@code batchSize} pending proposals whose {@code expires_at} has passed.
     *
     * @param batchSize maximum proposals to expire (1–{@value #MAX_BATCH})
     * @return number of proposals expired
     */
    public int expireDue(int batchSize) {
        checkBatch(batchSize);
        Instant now = UtcTimes.now(clock);
        return db.write(em -> {
            List<UUID> ids = lockedIds(em, "SELECT id FROM " + db.qualified("dai_change_proposal")
                    + " WHERE state IN ('PROPOSED', 'EDITED', 'AWAITING_APPROVAL') AND expires_at <= ?1"
                    + " ORDER BY expires_at LIMIT ?2 FOR UPDATE SKIP LOCKED", now, batchSize);
            int expired = 0;
            for (UUID id : ids) {
                ChangeProposal proposal = em.find(ChangeProposal.class, id);
                if (proposal != null && proposal.getState().isPending() && !now.isBefore(proposal.getExpiresAt())) {
                    proposal.expire(now);
                    proposal.retainUntil(now.plus(terminalRetention));
                    expired++;
                }
            }
            return expired;
        });
    }

    /**
     * Crash reconciliation scan (LLD-11 §10): proposals stuck in APPLYING whose confirmation is older than the
     * given age. The caller checks the host's versioning for the revision and marks each APPLIED or FAILED.
     *
     * @param olderThan minimum age of {@code confirmed_at}
     * @param limit     maximum ids (1–{@value #MAX_BATCH})
     * @return proposal ids, oldest confirmation first
     */
    public List<UUID> applyingSince(Duration olderThan, int limit) {
        checkBatch(limit);
        Instant before = UtcTimes.now(clock).minus(olderThan);
        return db.read(em -> em.createQuery("select p.id from ChangeProposal p where p.state = :state "
                        + "and p.confirmedAt < :before order by p.confirmedAt", UUID.class)
                .setParameter("state", ProposalState.APPLYING)
                .setParameter("before", before)
                .setMaxResults(limit)
                .getResultList());
    }

    /**
     * Deletes up to {@code batchSize} terminal proposals whose retention has ended (records, events and approvals
     * go with them through {@code ON DELETE CASCADE}).
     *
     * @param batchSize maximum proposals to delete (1–{@value #MAX_BATCH})
     * @return number of proposals deleted
     */
    public int purgeRetained(int batchSize) {
        checkBatch(batchSize);
        Instant now = UtcTimes.now(clock);
        String table = db.qualified("dai_change_proposal");
        return db.write(em -> em.createNativeQuery("DELETE FROM " + table + " WHERE id IN (SELECT id FROM " + table
                        + " WHERE retention_until < ?1 AND state IN ('APPLIED', 'REJECTED', 'EXPIRED', 'CONFLICT', 'FAILED')"
                        + " ORDER BY retention_until LIMIT ?2 FOR UPDATE SKIP LOCKED)")
                .setParameter(1, now)
                .setParameter(2, batchSize)
                .executeUpdate());
    }

    // ---------------------------------------------------------------------------------------------- helpers

    /** A proposal transition executed inside the store's transaction. */
    @FunctionalInterface
    public interface Transition {
        /**
         * Applies the transition.
         *
         * @param proposal the managed, row-locked aggregate
         * @param now      current time of the store's clock
         */
        void apply(ChangeProposal proposal, Instant now);
    }

    private static List<UUID> lockedIds(EntityManager em, String sql, Instant now, int limit) {
        List<?> rows = em.createNativeQuery(sql)
                .setParameter(1, now)
                .setParameter(2, limit)
                .getResultList();
        List<UUID> ids = new ArrayList<>(rows.size());
        for (Object row : rows) {
            ids.add(row instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(row)));
        }
        return ids;
    }

    private static ChangeProposal initialized(ChangeProposal proposal) {
        proposal.initializeAssociations();
        return proposal;
    }

    private static Slice<ProposalSummary> summaries(Slice<ChangeProposal> slice) {
        return new Slice<>(slice.items().stream().map(ProposalSummary::of).toList(), slice.page(), slice.hasMore());
    }

    private static void checkBatch(int size) {
        if (size < 1 || size > MAX_BATCH) {
            throw new IllegalArgumentException("batch size must be between 1 and " + MAX_BATCH);
        }
    }
}
