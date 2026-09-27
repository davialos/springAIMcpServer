package com.springaimcpservercommon.persistence.config;

import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.persistence.unit.DaiStore;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import org.hibernate.query.NativeQuery;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The single write path of the configuration lifecycle (LLD-09): resources and draft revisions, submit and review
 * (four-eyes), publish/deprecate/retire/suspend/rollback as snapshot generations, catalog references and
 * dependencies, drift queries, and cluster node state.
 *
 * <h2>Snapshot generations</h2>
 * Every operation that changes the live set (or the status of a live resource) runs in ONE transaction that
 * <ol>
 *   <li>takes a transaction-scoped PostgreSQL advisory lock (bounded by {@code lock_timeout}), so concurrent
 *       publishers are serialised — otherwise two publishes could each compute a manifest without the other's
 *       change;</li>
 *   <li>applies the revision state changes (superseding the previous live revision is flushed before the new one
 *       goes live, so {@code uq_resource_revision_one_published} is never violated transiently);</li>
 *   <li>checks that every live revision only depends on live resources;</li>
 *   <li>inserts generation {@code max + 1} (contiguous, even after rolled-back attempts) with the manifest hash of
 *       the FULL live set ({@link SnapshotManifest}) and one {@code dai_snapshot_entry} per live resource.</li>
 * </ol>
 * Nodes poll {@link #latestGeneration()} and load {@link #loadSnapshot(long)} (ADR-0006).
 */
public final class ConfigStore {

    /** Advisory lock class id ("DAI\0"); the object id distinguishes locks of this library. */
    static final int LOCK_CLASS = 0x44414900;
    /** Advisory lock object id of the publish lock. */
    static final int PUBLISH_LOCK = 1;

    /** Default upper bound for waiting on the publish lock. */
    public static final Duration DEFAULT_PUBLISH_LOCK_TIMEOUT = Duration.ofSeconds(15);

    private static final Set<RevisionState> LIVE = RevisionState.liveStates();
    private static final Set<RevisionState> DRIFT_RELEVANT = Set.of(RevisionState.DRAFT, RevisionState.IN_REVIEW,
            RevisionState.APPROVED, RevisionState.PUBLISHED, RevisionState.DEPRECATED);

    private final DaiStore store;
    private final Clock clock;
    private final Duration publishLockTimeout;
    private final String insertSnapshotSql;
    private final String heartbeatSql;
    private final String pruneNodesSql;

    /**
     * Creates the store with the system UTC clock and the default publish lock timeout.
     *
     * @param store the persistence unit
     */
    public ConfigStore(DaiStore store) {
        this(store, Clock.systemUTC(), DEFAULT_PUBLISH_LOCK_TIMEOUT);
    }

    /**
     * Creates the store.
     *
     * @param store              the persistence unit
     * @param clock              time source
     * @param publishLockTimeout maximum wait for the publish lock (and row locks of a publish)
     */
    public ConfigStore(DaiStore store, Clock clock, Duration publishLockTimeout) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.publishLockTimeout = Objects.requireNonNull(publishLockTimeout, "publishLockTimeout");
        if (publishLockTimeout.isNegative() || publishLockTimeout.isZero()) {
            throw new IllegalArgumentException("publishLockTimeout must be positive");
        }
        String schema = store.schema();
        this.insertSnapshotSql = "INSERT INTO " + schema + ".dai_snapshot"
                + " (generation, published_by, published_at, reason, rollback_of_generation, manifest_hash)"
                + " OVERRIDING SYSTEM VALUE VALUES (?1, ?2, ?3, ?4, ?5, ?6)";
        this.heartbeatSql = """
                INSERT INTO %s.dai_node_state (node_id, applied_generation, host_application, host_version,
                                               library_version, started_at, heartbeat_at)
                VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
                ON CONFLICT (node_id) DO UPDATE
                   SET applied_generation = EXCLUDED.applied_generation,
                       host_application   = EXCLUDED.host_application,
                       host_version       = EXCLUDED.host_version,
                       library_version    = EXCLUDED.library_version,
                       started_at         = EXCLUDED.started_at,
                       heartbeat_at       = EXCLUDED.heartbeat_at
                """.formatted(schema);
        this.pruneNodesSql = "DELETE FROM " + schema + ".dai_node_state WHERE heartbeat_at < ?1";
    }

    // =========================================================================================================
    // Authoring
    // =========================================================================================================

    /**
     * Creates a resource together with its first draft revision (revision 1).
     *
     * @param workspaceId owning workspace
     * @param kind        resource kind
     * @param slug        slug unique per workspace and kind
     * @param content     draft content
     * @param authorId    author (also recorded as resource creator)
     * @return the draft revision
     */
    public RevisionView createResource(UUID workspaceId, ResourceKind kind, String slug, DraftContent content,
                                       UUID authorId) {
        Instant now = clock.instant();
        Resource resource = Resource.create(workspaceId, kind, slug, authorId, now);
        ResourceRevision draft = ResourceRevision.draft(resource, 1, content, null, authorId, now);
        return store.transactions().execute(status -> {
            EntityManager em = store.entityManager();
            em.persist(resource);
            em.persist(draft);
            em.flush();
            return draft.view();
        });
    }

    /**
     * Creates the next draft revision of an existing resource (numbering serialised by a row lock on the resource).
     *
     * @param resourceId        resource
     * @param basedOnRevisionId revision the draft derives from (e.g. the live or a rejected one), if any; must
     *                          belong to the same resource
     * @param content           draft content
     * @param authorId          author
     * @return the new draft
     */
    public RevisionView createDraft(UUID resourceId, @Nullable UUID basedOnRevisionId, DraftContent content,
                                    UUID authorId) {
        Instant now = clock.instant();
        return store.transactions().execute(status -> {
            EntityManager em = store.entityManager();
            Resource resource = em.find(Resource.class, Objects.requireNonNull(resourceId, "resourceId"),
                    LockModeType.PESSIMISTIC_WRITE);
            if (resource == null) {
                throw new NoSuchElementException("resource " + resourceId + " does not exist");
            }
            if (basedOnRevisionId != null
                    && !requireRevision(em, basedOnRevisionId).getResourceId().equals(resourceId)) {
                throw new IllegalArgumentException("based-on revision belongs to another resource");
            }
            Integer max = em.createQuery("select max(r.revisionNo) from ResourceRevision r where r.resourceId = :id",
                            Integer.class)
                    .setParameter("id", resourceId)
                    .getSingleResult();
            ResourceRevision draft = ResourceRevision.draft(resource, (max == null ? 0 : max) + 1, content,
                    basedOnRevisionId, authorId, now);
            em.persist(draft);
            em.flush();
            return draft.view();
        });
    }

    /**
     * Replaces the content of a draft (author only, optimistic lock).
     *
     * @param revisionId         draft revision
     * @param expectedRowVersion version the editor read
     * @param editorId           acting principal (must be the author)
     * @param content            new content
     * @return the updated draft
     * @throws OptimisticLockException  if changed concurrently
     * @throws ConfigLifecycleException if not a draft or not the author
     */
    public RevisionView updateDraft(UUID revisionId, long expectedRowVersion, UUID editorId, DraftContent content) {
        return store.transactions().execute(status -> {
            EntityManager em = store.entityManager();
            ResourceRevision revision = requireRevision(em, revisionId);
            requireVersion(revision, expectedRowVersion);
            revision.editDraft(editorId, content);
            em.flush();
            return revision.view();
        });
    }

    /**
     * Replaces the pinned catalog references of a draft (LLD-03 §6).
     *
     * @param revisionId draft revision
     * @param references element → signature hash at authoring time
     */
    public void replaceReferences(UUID revisionId, Map<CatalogElementRef, String> references) {
        Objects.requireNonNull(references, "references");
        List<RevisionReference> rows = references.entrySet().stream()
                .map(e -> RevisionReference.pin(revisionId, e.getKey(), e.getValue()))
                .toList();
        store.transactions().executeWithoutResult(status -> {
            EntityManager em = store.entityManager();
            requireRevision(em, revisionId).requireDraft();
            em.createQuery("delete from RevisionReference r where r.id.revisionId = :id")
                    .setParameter("id", revisionId)
                    .executeUpdate();
            rows.forEach(em::persist);
        });
    }

    /**
     * Replaces the resource dependencies of a draft.
     *
     * @param revisionId           draft revision
     * @param dependsOnResourceIds resources the draft uses (not its own resource)
     */
    public void replaceDependencies(UUID revisionId, Set<UUID> dependsOnResourceIds) {
        Objects.requireNonNull(dependsOnResourceIds, "dependsOnResourceIds");
        store.transactions().executeWithoutResult(status -> {
            EntityManager em = store.entityManager();
            ResourceRevision revision = requireRevision(em, revisionId);
            revision.requireDraft();
            if (dependsOnResourceIds.contains(revision.getResourceId())) {
                throw new IllegalArgumentException("a revision cannot depend on its own resource");
            }
            em.createQuery("delete from RevisionDependency d where d.id.revisionId = :id")
                    .setParameter("id", revisionId)
                    .executeUpdate();
            dependsOnResourceIds.forEach(target -> em.persist(new RevisionDependency(revisionId, target)));
        });
    }

    /**
     * Pinned references of a revision.
     *
     * @param revisionId revision
     * @return element → signature hash
     */
    public Map<CatalogElementRef, String> references(UUID revisionId) {
        return store.readOnlyTransactions().execute(status -> {
            Map<CatalogElementRef, String> result = new LinkedHashMap<>();
            store.entityManager()
                    .createQuery("select r from RevisionReference r where r.id.revisionId = :id order by r.id.elementRef",
                            RevisionReference.class)
                    .setParameter("id", revisionId)
                    .getResultStream()
                    .forEach(r -> result.put(CatalogElementRef.parse(r.getId().getElementRef()), r.getSignatureHash()));
            return result;
        });
    }

    /**
     * Resource dependencies of a revision.
     *
     * @param revisionId revision
     * @return ids of the resources it uses
     */
    public Set<UUID> dependencies(UUID revisionId) {
        return store.readOnlyTransactions().execute(status -> Set.copyOf(store.entityManager()
                .createQuery("select d.id.dependsOnResourceId from RevisionDependency d where d.id.revisionId = :id",
                        UUID.class)
                .setParameter("id", revisionId)
                .getResultList()));
    }

    // =========================================================================================================
    // Review
    // =========================================================================================================

    /**
     * Submits a draft for review; its content becomes immutable.
     *
     * @param revisionId         draft revision
     * @param expectedRowVersion version the submitter reviewed
     * @param riskScore          optional risk score 0..100
     * @return the revision in IN_REVIEW
     */
    public RevisionView submit(UUID revisionId, long expectedRowVersion, @Nullable Integer riskScore) {
        Instant now = clock.instant();
        return store.transactions().execute(status -> {
            EntityManager em = store.entityManager();
            ResourceRevision revision = requireRevision(em, revisionId);
            requireVersion(revision, expectedRowVersion);
            revision.submit(riskScore, now);
            em.flush();
            return revision.view();
        });
    }

    /**
     * Records a review decision. The revision row is locked so concurrent reviews count the quorum consistently.
     * An approval moves the revision to APPROVED once {@code requiredApprovals} distinct reviewers approved; a
     * rejection or change request moves it to REJECTED.
     *
     * @param revisionId        revision in review
     * @param reviewerId        reviewer (must not be the author)
     * @param decision          decision
     * @param comment           mandatory unless approving
     * @param requiredApprovals approvals the publish policy requires (SEC-01 §6), ≥ 1
     * @return the review and the resulting revision
     * @throws SegregationOfDutiesException if the reviewer is the author
     */
    public ReviewOutcome review(UUID revisionId, UUID reviewerId, ReviewDecision decision, @Nullable String comment,
                                int requiredApprovals) {
        if (requiredApprovals < 1) {
            throw new IllegalArgumentException("requiredApprovals must be >= 1");
        }
        Instant now = clock.instant();
        return store.transactions().execute(status -> {
            EntityManager em = store.entityManager();
            ResourceRevision revision = em.find(ResourceRevision.class, Objects.requireNonNull(revisionId, "revisionId"),
                    LockModeType.PESSIMISTIC_WRITE);
            if (revision == null) {
                throw new NoSuchElementException("revision " + revisionId + " does not exist");
            }
            Review review = Review.record(revision, reviewerId, decision, comment, now);
            em.persist(review);
            em.flush();
            long approvals = em.createQuery("select count(r) from Review r where r.revisionId = :id"
                            + " and r.decision = :approved", Long.class)
                    .setParameter("id", revisionId)
                    .setParameter("approved", ReviewDecision.APPROVED)
                    .getSingleResult();
            if (decision != ReviewDecision.APPROVED) {
                revision.reject();
            } else if (approvals >= requiredApprovals) {
                revision.approve(now);
            }
            em.flush();
            return new ReviewOutcome(review.view(), revision.view(), approvals);
        });
    }

    /**
     * Approves a submitted revision without reviews. Only for changes the publish policy (SEC-01 §6) exempts from
     * approval — that decision is the caller's.
     *
     * @param revisionId revision in review
     * @return the approved revision
     */
    public RevisionView approveWithoutReview(UUID revisionId) {
        Instant now = clock.instant();
        return store.transactions().execute(status -> {
            ResourceRevision revision = requireRevision(store.entityManager(), revisionId);
            revision.approve(now);
            store.entityManager().flush();
            return revision.view();
        });
    }

    /**
     * Marks approvals older than the cut-off as STALE (LLD-09 §2 "expire (30d)"); run by the maintenance job.
     *
     * @param approvedBefore approvals strictly older than this expire
     * @return number of expired revisions
     */
    public int expireApprovals(Instant approvedBefore) {
        Objects.requireNonNull(approvedBefore, "approvedBefore");
        Integer expired = store.transactions().execute(status -> {
            List<ResourceRevision> stale = store.entityManager()
                    .createQuery("select r from ResourceRevision r where r.state = :approved and r.approvedAt < :cutoff",
                            ResourceRevision.class)
                    .setParameter("approved", RevisionState.APPROVED)
                    .setParameter("cutoff", approvedBefore)
                    .getResultList();
            stale.forEach(ResourceRevision::expire);
            return stale.size();
        });
        return expired == null ? 0 : expired;
    }

    /**
     * Reviews recorded for a revision.
     *
     * @param revisionId revision
     * @return reviews ordered by decision time
     */
    public List<ReviewView> reviews(UUID revisionId) {
        return store.readOnlyTransactions().execute(status -> store.entityManager()
                .createQuery("select r from Review r where r.revisionId = :id order by r.decidedAt", Review.class)
                .setParameter("id", revisionId)
                .getResultStream()
                .map(Review::view)
                .toList());
    }

    /**
     * Revisions waiting for review in a workspace (the review inbox).
     *
     * @param workspaceId workspace
     * @return IN_REVIEW revisions ordered by submit time
     */
    public List<RevisionView> reviewInbox(UUID workspaceId) {
        return store.readOnlyTransactions().execute(status -> store.entityManager()
                .createQuery("select r from ResourceRevision r, Resource res where res.id = r.resourceId"
                        + " and res.workspaceId = :ws and r.state = :inReview order by r.submittedAt", ResourceRevision.class)
                .setParameter("ws", workspaceId)
                .setParameter("inReview", RevisionState.IN_REVIEW)
                .getResultStream()
                .map(ResourceRevision::view)
                .toList());
    }

    // =========================================================================================================
    // Publishing (snapshot generations)
    // =========================================================================================================

    /**
     * Publishes an approved revision: the resource's current live revision (if any) becomes SUPERSEDED, this one
     * PUBLISHED, and generation g+1 with the full live set is written — all in one transaction.
     *
     * @param revisionId  APPROVED revision
     * @param publishedBy acting principal
     * @param reason      optional reason
     * @return the new generation
     * @throws ConfigLifecycleException if not approved, the resource is retired or a dependency is not live
     */
    public PublishResult publish(UUID revisionId, UUID publishedBy, @Nullable String reason) {
        Instant now = clock.instant();
        return newGeneration(publishedBy, now, reason, null, em -> {
            ResourceRevision revision = requireRevision(em, revisionId);
            requireResource(em, revision.getResourceId()).requireNotRetired();
            if (revision.getState() != RevisionState.APPROVED) {
                throw new ConfigLifecycleException("only an APPROVED revision can be published (revision "
                        + revisionId + " is " + revision.getState() + ")");
            }
            liveRevision(em, revision.getResourceId()).ifPresent(current -> {
                current.supersede();
                em.flush();
            });
            revision.publish(now);
        });
    }

    /**
     * Deprecates the live revision of a resource (it stays served, flagged for removal) as a new generation.
     *
     * @param resourceId resource whose live revision is PUBLISHED
     * @param by         acting principal
     * @param reason     optional reason
     * @return the new generation
     */
    public PublishResult deprecate(UUID resourceId, UUID by, @Nullable String reason) {
        Instant now = clock.instant();
        return newGeneration(by, now, reason, null, em -> requireLive(em, resourceId).deprecate());
    }

    /**
     * Retires a resource: its DEPRECATED live revision (if any) becomes RETIRED and the resource leaves the live
     * set for good, as a new generation. A PUBLISHED revision must be deprecated first.
     *
     * @param resourceId resource
     * @param by         acting principal
     * @param reason     optional reason
     * @return the new generation
     */
    public PublishResult retire(UUID resourceId, UUID by, @Nullable String reason) {
        Instant now = clock.instant();
        return newGeneration(by, now, reason, null, em -> {
            Resource resource = requireResource(em, resourceId);
            liveRevision(em, resourceId).ifPresent(live -> live.retire(now));
            resource.retire(by, now);
        });
    }

    /**
     * Suspends a resource (e.g. catalog drift, LLD-03 §6) as a new generation; it stays in the manifest with status
     * SUSPENDED so nodes stop serving it.
     *
     * @param resourceId resource
     * @param reason     mandatory reason
     * @param by         acting principal
     * @return the new generation
     */
    public PublishResult suspend(UUID resourceId, String reason, UUID by) {
        Instant now = clock.instant();
        return newGeneration(by, now, reason, null, em -> requireResource(em, resourceId).suspend(reason, by, now));
    }

    /**
     * Lifts a suspension as a new generation.
     *
     * @param resourceId resource
     * @param by         acting principal
     * @param reason     optional reason
     * @return the new generation
     */
    public PublishResult resume(UUID resourceId, UUID by, @Nullable String reason) {
        Instant now = clock.instant();
        return newGeneration(by, now, reason, null, em -> requireResource(em, resourceId).resume(by, now));
    }

    /**
     * Rolls the live set back to the one of an earlier generation, as a NEW generation with
     * {@code rollback_of_generation} set (LLD-09 §2: "rollback = publish of a previous revision"). Live revisions not
     * in the target become SUPERSEDED; target revisions that are no longer live are republished.
     *
     * @param toGeneration generation to restore
     * @param by           acting principal
     * @param reason       mandatory reason
     * @return the new generation
     * @throws ConfigLifecycleException if the generation does not exist or a target revision/resource is retired
     */
    public PublishResult rollback(long toGeneration, UUID by, String reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("a rollback needs a reason");
        }
        Instant now = clock.instant();
        return newGeneration(by, now, reason, toGeneration, em -> {
            if (em.find(Snapshot.class, toGeneration) == null) {
                throw new ConfigLifecycleException("generation " + toGeneration + " does not exist");
            }
            Map<UUID, UUID> target = new HashMap<>();
            em.createQuery("select e from SnapshotEntry e where e.id.generation = :g", SnapshotEntry.class)
                    .setParameter("g", toGeneration)
                    .getResultStream()
                    .forEach(e -> target.put(e.getId().getResourceId(), e.getRevisionId()));
            for (ResourceRevision live : liveRevisions(em)) {
                if (!live.getId().equals(target.get(live.getResourceId()))) {
                    live.supersede();
                }
            }
            em.flush();
            for (Map.Entry<UUID, UUID> entry : target.entrySet()) {
                requireResource(em, entry.getKey()).requireNotRetired();
                ResourceRevision revision = requireRevision(em, entry.getValue());
                if (!revision.getState().isLive()) {
                    revision.publish(now); // SUPERSEDED → PUBLISHED; any other state is rejected by the aggregate
                }
            }
        });
    }

    private PublishResult newGeneration(UUID by, Instant now, @Nullable String reason, @Nullable Long rollbackOf,
                                        Consumer<EntityManager> mutation) {
        Objects.requireNonNull(by, "by");
        return store.transactions().execute(status -> {
            EntityManager em = store.entityManager();
            acquirePublishLock(em);
            mutation.accept(em);
            em.flush();
            requireLiveSetClosed(em);

            Map<UUID, UUID> entries = new TreeMap<>(Comparator.comparing(UUID::toString));
            em.createQuery("select r.resourceId, r.id from ResourceRevision r where r.state in :live", Object[].class)
                    .setParameter("live", LIVE)
                    .getResultStream()
                    .forEach(row -> entries.put((UUID) row[0], (UUID) row[1]));
            String manifestHash = SnapshotManifest.hash(entries);

            Long max = em.createQuery("select max(s.generation) from Snapshot s", Long.class).getSingleResult();
            long generation = (max == null ? 0L : max) + 1L;
            NativeQuery<?> insert = em.createNativeQuery(insertSnapshotSql).unwrap(NativeQuery.class);
            insert.setParameter(1, generation, Long.class);
            insert.setParameter(2, by, UUID.class);
            insert.setParameter(3, now, Instant.class);
            insert.setParameter(4, reason, String.class);
            insert.setParameter(5, rollbackOf, Long.class);
            insert.setParameter(6, manifestHash, String.class);
            insert.executeUpdate();
            entries.forEach((resourceId, revisionId) -> em.persist(new SnapshotEntry(generation, resourceId, revisionId)));
            em.flush();
            return new PublishResult(generation, manifestHash, entries.size(), rollbackOf);
        });
    }

    private void acquirePublishLock(EntityManager em) {
        em.createNativeQuery("SELECT set_config('lock_timeout', ?1, true)")
                .setParameter(1, publishLockTimeout.toMillis() + "ms")
                .getSingleResult();
        em.createNativeQuery("SELECT count(*) FROM (SELECT pg_advisory_xact_lock(?1, ?2)) AS l")
                .setParameter(1, LOCK_CLASS)
                .setParameter(2, PUBLISH_LOCK)
                .getSingleResult();
    }

    private static void requireLiveSetClosed(EntityManager em) {
        List<Object[]> dangling = em.createQuery("select d.id.revisionId, d.id.dependsOnResourceId"
                        + " from RevisionDependency d, ResourceRevision r"
                        + " where r.id = d.id.revisionId and r.state in :live"
                        + " and not exists (select r2.id from ResourceRevision r2"
                        + "   where r2.resourceId = d.id.dependsOnResourceId and r2.state in :live)", Object[].class)
                .setParameter("live", LIVE)
                .setMaxResults(5)
                .getResultList();
        if (!dangling.isEmpty()) {
            List<String> pairs = new ArrayList<>();
            dangling.forEach(row -> pairs.add(row[0] + "->" + row[1]));
            throw new ConfigLifecycleException("live revisions depend on resources that are not live: " + pairs);
        }
    }

    // =========================================================================================================
    // Reading
    // =========================================================================================================

    /**
     * The latest published generation, polled by every node's snapshot watcher.
     *
     * @return the latest generation, empty before the first publish
     */
    public OptionalLong latestGeneration() {
        Long max = store.readOnlyTransactions().execute(status -> store.entityManager()
                .createQuery("select max(s.generation) from Snapshot s", Long.class)
                .getSingleResult());
        return max == null ? OptionalLong.empty() : OptionalLong.of(max);
    }

    /**
     * Loads a generation with the specs of all its revisions, in one read-only transaction.
     *
     * @param generation generation to load
     * @return the snapshot, empty if the generation does not exist
     */
    public Optional<PublishedSnapshot> loadSnapshot(long generation) {
        return store.readOnlyTransactions().execute(status -> {
            EntityManager em = store.entityManager();
            Snapshot snapshot = em.find(Snapshot.class, generation);
            if (snapshot == null) {
                return Optional.<PublishedSnapshot>empty();
            }
            List<PublishedResource> resources = new ArrayList<>();
            em.createQuery("select res, r from SnapshotEntry e, ResourceRevision r, Resource res"
                            + " where e.id.generation = :g and r.id = e.revisionId and res.id = e.id.resourceId",
                            Object[].class)
                    .setParameter("g", generation)
                    .getResultStream()
                    .forEach(row -> resources.add(published((Resource) row[0], (ResourceRevision) row[1])));
            resources.sort(Comparator.comparing(r -> r.resourceId().toString()));
            return Optional.of(new PublishedSnapshot(snapshot.getGeneration(), snapshot.getPublishedAt(),
                    snapshot.getPublishedBy(), snapshot.getReason(), snapshot.getRollbackOfGeneration(),
                    snapshot.getManifestHash(), resources));
        });
    }

    private static PublishedResource published(Resource res, ResourceRevision r) {
        return new PublishedResource(res.getId(), res.getWorkspaceId(), res.getKind(), res.getSlug(), res.getStatus(),
                res.getSuspendedReason(), r.getId(), r.getRevisionNo(), r.getState(), r.getSpec(),
                r.getSpecSchemaVersion(), r.getSpecHash(), r.getPublishedAt());
    }

    /**
     * Finds a resource.
     *
     * @param resourceId resource id
     * @return the resource, if any
     */
    public Optional<ResourceView> findResource(UUID resourceId) {
        return store.readOnlyTransactions().execute(status ->
                Optional.ofNullable(store.entityManager().find(Resource.class, resourceId)).map(Resource::view));
    }

    /**
     * Finds a resource by its natural key.
     *
     * @param workspaceId workspace
     * @param kind        kind
     * @param slug        slug
     * @return the resource, if any
     */
    public Optional<ResourceView> findResource(UUID workspaceId, ResourceKind kind, String slug) {
        return store.readOnlyTransactions().execute(status -> store.entityManager()
                .createQuery("select r from Resource r where r.workspaceId = :ws and r.kind = :kind and r.slug = :slug",
                        Resource.class)
                .setParameter("ws", workspaceId)
                .setParameter("kind", kind)
                .setParameter("slug", slug)
                .getResultStream()
                .findFirst()
                .map(Resource::view));
    }

    /**
     * Lists the resources of a workspace.
     *
     * @param workspaceId workspace
     * @return resources ordered by kind and slug
     */
    public List<ResourceView> resources(UUID workspaceId) {
        return store.readOnlyTransactions().execute(status -> store.entityManager()
                .createQuery("select r from Resource r where r.workspaceId = :ws order by r.kind, r.slug", Resource.class)
                .setParameter("ws", workspaceId)
                .getResultStream()
                .map(Resource::view)
                .toList());
    }

    /**
     * Finds a revision.
     *
     * @param revisionId revision id
     * @return the revision, if any
     */
    public Optional<RevisionView> findRevision(UUID revisionId) {
        return store.readOnlyTransactions().execute(status ->
                Optional.ofNullable(store.entityManager().find(ResourceRevision.class, revisionId))
                        .map(ResourceRevision::view));
    }

    /**
     * All revisions of a resource (version history).
     *
     * @param resourceId resource
     * @return revisions, newest first
     */
    public List<RevisionView> revisions(UUID resourceId) {
        return store.readOnlyTransactions().execute(status -> store.entityManager()
                .createQuery("select r from ResourceRevision r where r.resourceId = :id order by r.revisionNo desc",
                        ResourceRevision.class)
                .setParameter("id", resourceId)
                .getResultStream()
                .map(ResourceRevision::view)
                .toList());
    }

    /**
     * The live (PUBLISHED or DEPRECATED) revision of a resource.
     *
     * @param resourceId resource
     * @return the live revision, if any
     */
    public Optional<RevisionView> liveRevision(UUID resourceId) {
        return store.readOnlyTransactions().execute(status ->
                liveRevision(store.entityManager(), resourceId).map(ResourceRevision::view));
    }

    // =========================================================================================================
    // Drift detection (LLD-03 §6)
    // =========================================================================================================

    /**
     * All catalog references pinned by live revisions; compare with the current scan after each deploy.
     *
     * @return pinned references ordered by element
     */
    public List<PinnedReference> liveReferences() {
        return pinned(null, LIVE);
    }

    /**
     * Revisions (drafts, in review, approved and live) that pin any of the given elements — e.g. the elements whose
     * signature changed or that disappeared in the current scan.
     *
     * @param elementRefs changed or removed elements
     * @return affected pinned references
     */
    public List<PinnedReference> revisionsReferencing(Collection<CatalogElementRef> elementRefs) {
        Objects.requireNonNull(elementRefs, "elementRefs");
        if (elementRefs.isEmpty()) {
            return List.of();
        }
        return pinned(elementRefs.stream().map(CatalogElementRef::toString).toList(), DRIFT_RELEVANT);
    }

    private List<PinnedReference> pinned(@Nullable List<String> refs, Set<RevisionState> states) {
        String jpql = "select ref, r from RevisionReference ref, ResourceRevision r"
                + " where r.id = ref.id.revisionId and r.state in :states"
                + (refs == null ? "" : " and ref.id.elementRef in :refs")
                + " order by ref.id.elementRef, r.id";
        return store.readOnlyTransactions().execute(status -> {
            var query = store.entityManager().createQuery(jpql, Object[].class).setParameter("states", states);
            if (refs != null) {
                query.setParameter("refs", refs);
            }
            return query.getResultStream().map(row -> {
                RevisionReference ref = (RevisionReference) row[0];
                ResourceRevision revision = (ResourceRevision) row[1];
                return new PinnedReference(revision.getId(), revision.getResourceId(), revision.getState(),
                        CatalogElementRef.parse(ref.getId().getElementRef()), ref.getSignatureHash());
            }).toList();
        });
    }

    // =========================================================================================================
    // Cluster node state (F-73)
    // =========================================================================================================

    /**
     * Upserts this node's heartbeat row ({@code heartbeat_at} = now).
     *
     * @param heartbeat node report
     */
    public void heartbeat(NodeHeartbeat heartbeat) {
        Objects.requireNonNull(heartbeat, "heartbeat");
        Instant now = clock.instant();
        store.transactions().executeWithoutResult(status -> {
            NativeQuery<?> upsert = store.entityManager().createNativeQuery(heartbeatSql).unwrap(NativeQuery.class);
            upsert.setParameter(1, heartbeat.nodeId(), String.class);
            upsert.setParameter(2, heartbeat.appliedGeneration(), Long.class);
            upsert.setParameter(3, heartbeat.hostApplication(), String.class);
            upsert.setParameter(4, heartbeat.hostVersion(), String.class);
            upsert.setParameter(5, heartbeat.libraryVersion(), String.class);
            upsert.setParameter(6, heartbeat.startedAt(), Instant.class);
            upsert.setParameter(7, now, Instant.class);
            upsert.executeUpdate();
        });
    }

    /**
     * Cluster convergence status.
     *
     * @param aliveWithin nodes with a heartbeat within this window count as alive
     * @return latest generation and per-node status
     */
    public ClusterStatus clusterStatus(Duration aliveWithin) {
        Objects.requireNonNull(aliveWithin, "aliveWithin");
        Instant aliveSince = clock.instant().minus(aliveWithin);
        return store.readOnlyTransactions().execute(status -> {
            EntityManager em = store.entityManager();
            Long latest = em.createQuery("select max(s.generation) from Snapshot s", Long.class).getSingleResult();
            List<ClusterStatus.Node> nodes = em.createQuery("select n from NodeState n order by n.nodeId", NodeState.class)
                    .getResultStream()
                    .map(n -> new ClusterStatus.Node(n.getNodeId(), n.getAppliedGeneration(), n.getHostApplication(),
                            n.getHostVersion(), n.getLibraryVersion(), n.getStartedAt(), n.getHeartbeatAt(),
                            !n.getHeartbeatAt().isBefore(aliveSince),
                            Objects.equals(n.getAppliedGeneration(), latest)))
                    .toList();
            return new ClusterStatus(latest, nodes);
        });
    }

    /**
     * Deletes rows of nodes that have been silent for longer than the given duration.
     *
     * @param silentFor silence threshold
     * @return number of removed node rows
     */
    public int pruneNodes(Duration silentFor) {
        Instant cutoff = clock.instant().minus(Objects.requireNonNull(silentFor, "silentFor"));
        Integer removed = store.transactions().execute(status -> store.entityManager()
                .createNativeQuery(pruneNodesSql)
                .setParameter(1, cutoff)
                .executeUpdate());
        return removed == null ? 0 : removed;
    }

    // =========================================================================================================
    // Helpers
    // =========================================================================================================

    private static ResourceRevision requireRevision(EntityManager em, UUID revisionId) {
        ResourceRevision revision = em.find(ResourceRevision.class, Objects.requireNonNull(revisionId, "revisionId"));
        if (revision == null) {
            throw new NoSuchElementException("revision " + revisionId + " does not exist");
        }
        return revision;
    }

    private static Resource requireResource(EntityManager em, UUID resourceId) {
        Resource resource = em.find(Resource.class, Objects.requireNonNull(resourceId, "resourceId"));
        if (resource == null) {
            throw new NoSuchElementException("resource " + resourceId + " does not exist");
        }
        return resource;
    }

    private static ResourceRevision requireLive(EntityManager em, UUID resourceId) {
        requireResource(em, resourceId);
        return liveRevision(em, resourceId).orElseThrow(() ->
                new ConfigLifecycleException("resource " + resourceId + " has no live revision"));
    }

    private static Optional<ResourceRevision> liveRevision(EntityManager em, UUID resourceId) {
        return em.createQuery("select r from ResourceRevision r where r.resourceId = :id and r.state in :live",
                        ResourceRevision.class)
                .setParameter("id", resourceId)
                .setParameter("live", LIVE)
                .getResultStream()
                .findFirst();
    }

    private static List<ResourceRevision> liveRevisions(EntityManager em) {
        return em.createQuery("select r from ResourceRevision r where r.state in :live", ResourceRevision.class)
                .setParameter("live", LIVE)
                .getResultList();
    }

    private static void requireVersion(ResourceRevision revision, long expectedRowVersion) {
        if (revision.getRowVersion() != expectedRowVersion) {
            throw new OptimisticLockException("revision " + revision.getId() + " was changed concurrently");
        }
    }
}
