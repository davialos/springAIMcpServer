package com.springaimcpservercommon.persistence.config;

import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.hash.Sha256;
import com.springaimcpservercommon.persistence.config.support.DaiTestDatabase;
import com.springaimcpservercommon.persistence.config.support.MutableClock;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigStoreIT {

    private static final AtomicInteger SLUGS = new AtomicInteger();

    private static DaiTestDatabase db;
    private static MutableClock clock;
    private static ConfigStore config;
    private static UUID workspace;
    private static UUID author;
    private static UUID reviewer;
    private static UUID secondReviewer;

    @BeforeAll
    static void setUp() {
        db = DaiTestDatabase.create();
        clock = new MutableClock(Instant.parse("2026-09-28T08:00:00Z"));
        config = new ConfigStore(db.store(), clock, Duration.ofSeconds(10));
        workspace = db.workspace("config");
        author = db.user("author");
        reviewer = db.user("reviewer");
        secondReviewer = db.user("reviewer-2");
    }

    @AfterAll
    static void tearDown() {
        db.close();
    }

    private static RevisionView newResource(String spec) {
        return config.createResource(workspace, ResourceKind.QUERY, "q" + SLUGS.incrementAndGet(),
                DraftContent.of(spec), author);
    }

    /** Draft → submit → one approval → published; returns the publish result. */
    private static PublishResult publishThroughReview(RevisionView draft) {
        RevisionView submitted = config.submit(draft.id(), draft.rowVersion(), 10);
        config.review(submitted.id(), reviewer, ReviewDecision.APPROVED, null, 1);
        clock.advance(Duration.ofSeconds(1));
        return config.publish(submitted.id(), reviewer, "test");
    }

    private static long snapshotCount() {
        Long count = db.jdbc().queryForObject("SELECT count(*) FROM " + db.table("dai_snapshot"), Long.class);
        return count == null ? 0 : count;
    }

    @Test
    void publishCreatesContiguousGenerationsWithFullManifests() {
        long before = config.latestGeneration().orElse(0);

        RevisionView a1 = newResource("{\"sql\":\"a\"}");
        PublishResult g1 = publishThroughReview(a1);
        RevisionView b1 = newResource("{\"sql\":\"b\"}");
        PublishResult g2 = publishThroughReview(b1);
        RevisionView a2 = config.createDraft(a1.resourceId(), a1.id(), DraftContent.of("{\"sql\":\"a2\"}"), author);
        PublishResult g3 = publishThroughReview(a2);

        assertThat(List.of(g1.generation(), g2.generation(), g3.generation()))
                .containsExactly(before + 1, before + 2, before + 3);
        assertThat(config.latestGeneration()).hasValue(g3.generation());

        PublishedSnapshot s3 = config.loadSnapshot(g3.generation()).orElseThrow();
        assertThat(s3.isIntact()).isTrue();
        assertThat(s3.manifest()).containsEntry(a1.resourceId(), a2.id()).containsEntry(b1.resourceId(), b1.id());
        assertThat(s3.resources()).hasSize(g3.entryCount());
        assertThat(s3.manifestHash()).isEqualTo(g3.manifestHash());

        PublishedSnapshot s2 = config.loadSnapshot(g2.generation()).orElseThrow();
        assertThat(s2.manifest()).containsEntry(a1.resourceId(), a1.id()).containsEntry(b1.resourceId(), b1.id());
        assertThat(s2.isIntact()).isTrue();

        assertThat(config.findRevision(a1.id()).orElseThrow().state()).isEqualTo(RevisionState.SUPERSEDED);
        assertThat(config.liveRevision(a1.resourceId()).orElseThrow().id()).isEqualTo(a2.id());
    }

    @Test
    void concurrentPublishesAreSerialisedAndTheLastManifestContainsAll() throws Exception {
        List<RevisionView> approved = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            RevisionView draft = newResource("{\"n\":" + i + "}");
            RevisionView submitted = config.submit(draft.id(), draft.rowVersion(), null);
            approved.add(config.review(submitted.id(), reviewer, ReviewDecision.APPROVED, null, 1).revision());
        }
        long before = config.latestGeneration().orElse(0);
        List<Callable<PublishResult>> tasks = approved.stream()
                .<Callable<PublishResult>>map(r -> () -> config.publish(r.id(), reviewer, null))
                .toList();
        List<Long> generations = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
            for (Future<PublishResult> f : pool.invokeAll(tasks)) {
                generations.add(f.get().generation());
            }
        }
        assertThat(generations).containsExactlyInAnyOrder(before + 1, before + 2, before + 3, before + 4,
                before + 5, before + 6);
        PublishedSnapshot last = config.loadSnapshot(before + 6).orElseThrow();
        for (RevisionView r : approved) {
            assertThat(last.manifest()).containsEntry(r.resourceId(), r.id());
        }
        assertThat(last.isIntact()).isTrue();
    }

    @Test
    void onlyOnePublishedRevisionPerResourceIsAllowedByTheDatabase() {
        RevisionView first = newResource("{\"v\":1}");
        publishThroughReview(first);
        RevisionView second = config.createDraft(first.resourceId(), first.id(), DraftContent.of("{\"v\":2}"), author);
        RevisionView submitted = config.submit(second.id(), second.rowVersion(), null);
        config.review(submitted.id(), reviewer, ReviewDecision.APPROVED, null, 1);

        // bypass the store: force a second PUBLISHED revision directly
        assertThatThrownBy(() -> db.jdbc().update("UPDATE " + db.table("dai_resource_revision")
                        + " SET state = 'PUBLISHED', published_at = now() WHERE id = ?", second.id()))
                .satisfies(e -> assertThat(DaiTestDatabase.messages(e)).contains("uq_resource_revision_one_published"));
    }

    @Test
    void revisionsAreImmutableAfterSubmitInTheDatabase() {
        RevisionView draft = newResource("{\"v\":1}");
        config.submit(draft.id(), draft.rowVersion(), null);

        assertThatThrownBy(() -> db.jdbc().update("UPDATE " + db.table("dai_resource_revision")
                        + " SET spec = '{\"v\":2}'::jsonb WHERE id = ?", draft.id()))
                .satisfies(e -> assertThat(DaiTestDatabase.messages(e)).contains("is immutable"));
        assertThatThrownBy(() -> db.jdbc().update("UPDATE " + db.table("dai_resource_revision")
                        + " SET state = 'DRAFT' WHERE id = ?", draft.id()))
                .satisfies(e -> assertThat(DaiTestDatabase.messages(e)).contains("cannot return to DRAFT"));
        assertThatThrownBy(() -> config.updateDraft(draft.id(), draft.rowVersion() + 1, author,
                DraftContent.of("{\"v\":3}")))
                .isInstanceOf(ConfigLifecycleException.class);
    }

    @Test
    void segregationOfDutiesIsEnforcedInJavaAndByTheTrigger() {
        RevisionView draft = newResource("{\"v\":1}");
        RevisionView submitted = config.submit(draft.id(), draft.rowVersion(), null);

        assertThatThrownBy(() -> config.review(submitted.id(), author, ReviewDecision.APPROVED, null, 1))
                .isInstanceOf(SegregationOfDutiesException.class);
        assertThatThrownBy(() -> db.jdbc().update("INSERT INTO " + db.table("dai_review")
                        + " (id, revision_id, reviewer_id, decision) VALUES (?, ?, ?, 'APPROVED')",
                UUID.randomUUID(), submitted.id(), author))
                .satisfies(e -> assertThat(DaiTestDatabase.messages(e)).contains("segregation of duties"));
    }

    @Test
    void quorumOfTwoApprovalsAndRejection() {
        RevisionView draft = newResource("{\"v\":1}");
        RevisionView submitted = config.submit(draft.id(), draft.rowVersion(), null);

        ReviewOutcome first = config.review(submitted.id(), reviewer, ReviewDecision.APPROVED, null, 2);
        assertThat(first.revision().state()).isEqualTo(RevisionState.IN_REVIEW);
        ReviewOutcome second = config.review(submitted.id(), secondReviewer, ReviewDecision.APPROVED, null, 2);
        assertThat(second.revision().state()).isEqualTo(RevisionState.APPROVED);
        assertThat(second.approvals()).isEqualTo(2);

        RevisionView other = newResource("{\"v\":2}");
        RevisionView otherSubmitted = config.submit(other.id(), other.rowVersion(), null);
        ReviewOutcome rejected = config.review(otherSubmitted.id(), reviewer, ReviewDecision.CHANGES_REQUESTED,
                "please add a limit", 1);
        assertThat(rejected.revision().state()).isEqualTo(RevisionState.REJECTED);
    }

    @Test
    void updateDraftUsesOptimisticLockingAndKeepsTheHashStable() {
        RevisionView draft = newResource("{\"b\":1.50,\"a\":[1,2]}");
        RevisionView updated = config.updateDraft(draft.id(), draft.rowVersion(), author,
                DraftContent.of("{\"a\":[1,2],\"b\":1.5,\"c\":true}"));
        assertThat(updated.rowVersion()).isGreaterThan(draft.rowVersion());

        assertThatThrownBy(() -> config.updateDraft(draft.id(), draft.rowVersion(), author, DraftContent.of("{}")))
                .isInstanceOf(OptimisticLockException.class);

        RevisionView reloaded = config.findRevision(draft.id()).orElseThrow();
        assertThat(CanonicalSpec.of(reloaded.specJson()).hash()).isEqualTo(reloaded.specHash());
        assertThat(reloaded.specHash()).isEqualTo(CanonicalSpec.of("{\"c\":true,\"b\":1.500,\"a\":[1,2]}").hash());
    }

    @Test
    void rollbackRepublishesAnEarlierGenerationAsANewOne() {
        RevisionView x1 = newResource("{\"x\":1}");
        PublishResult gx1 = publishThroughReview(x1);
        RevisionView x2 = config.createDraft(x1.resourceId(), x1.id(), DraftContent.of("{\"x\":2}"), author);
        publishThroughReview(x2);
        RevisionView y1 = newResource("{\"y\":1}");
        publishThroughReview(y1);

        PublishResult rolledBack = config.rollback(gx1.generation(), author, "bad release");

        assertThat(rolledBack.rollbackOfGeneration()).isEqualTo(gx1.generation());
        assertThat(rolledBack.generation()).isEqualTo(config.latestGeneration().orElseThrow());
        PublishedSnapshot snapshot = config.loadSnapshot(rolledBack.generation()).orElseThrow();
        PublishedSnapshot target = config.loadSnapshot(gx1.generation()).orElseThrow();
        assertThat(snapshot.manifest()).isEqualTo(target.manifest());
        assertThat(snapshot.manifestHash()).isEqualTo(target.manifestHash());
        assertThat(snapshot.rollbackOfGeneration()).isEqualTo(gx1.generation());
        assertThat(config.findRevision(x1.id()).orElseThrow().state()).isEqualTo(RevisionState.PUBLISHED);
        assertThat(config.findRevision(x2.id()).orElseThrow().state()).isEqualTo(RevisionState.SUPERSEDED);
        assertThat(config.findRevision(y1.id()).orElseThrow().state()).isEqualTo(RevisionState.SUPERSEDED);

        assertThatThrownBy(() -> config.rollback(999_999, author, "nope"))
                .isInstanceOf(ConfigLifecycleException.class);
    }

    @Test
    void failedPublishLeavesNoGap() {
        RevisionView dependent = newResource("{\"uses\":\"missing\"}");
        RevisionView missing = newResource("{\"never\":\"published\"}");
        config.replaceDependencies(dependent.id(), Set.of(missing.resourceId()));
        RevisionView submitted = config.submit(dependent.id(), config.findRevision(dependent.id()).orElseThrow()
                .rowVersion(), null);
        config.review(submitted.id(), reviewer, ReviewDecision.APPROVED, null, 1);
        long before = config.latestGeneration().orElse(0);
        long rows = snapshotCount();

        assertThatThrownBy(() -> config.publish(submitted.id(), reviewer, null))
                .isInstanceOf(ConfigLifecycleException.class)
                .hasMessageContaining("not live");
        assertThat(snapshotCount()).isEqualTo(rows);

        PublishResult next = publishThroughReview(newResource("{\"ok\":true}"));
        assertThat(next.generation()).isEqualTo(before + 1);
    }

    @Test
    void deprecateSuspendAndRetireProduceGenerations() {
        RevisionView r = newResource("{\"life\":1}");
        publishThroughReview(r);

        PublishResult suspended = config.suspend(r.resourceId(), "catalog drift", author);
        PublishedResource entry = config.loadSnapshot(suspended.generation()).orElseThrow().resources().stream()
                .filter(p -> p.resourceId().equals(r.resourceId())).findFirst().orElseThrow();
        assertThat(entry.resourceStatus()).isEqualTo(ResourceStatus.SUSPENDED);
        config.resume(r.resourceId(), author, null);

        assertThatThrownBy(() -> config.retire(r.resourceId(), author, null)).isInstanceOf(ConfigLifecycleException.class);
        config.deprecate(r.resourceId(), author, "replaced by v2");
        PublishResult retired = config.retire(r.resourceId(), author, null);
        assertThat(config.loadSnapshot(retired.generation()).orElseThrow().manifest()).doesNotContainKey(r.resourceId());
        assertThat(config.findResource(r.resourceId()).orElseThrow().status()).isEqualTo(ResourceStatus.RETIRED);
    }

    @Test
    void driftQueriesFindPinnedReferences() {
        CatalogElementRef op = CatalogElementRef.parse("op:com.acme.OrderService#find(java.lang.Long)");
        CatalogElementRef entity = CatalogElementRef.entity("com.acme.Order");
        RevisionView draft = newResource("{\"drift\":1}");
        config.replaceReferences(draft.id(), Map.of(op, Sha256.of("sig-op"), entity, Sha256.of("sig-entity")));
        assertThat(config.references(draft.id())).containsEntry(op, Sha256.of("sig-op"));

        assertThat(config.revisionsReferencing(List.of(op)))
                .extracting(PinnedReference::revisionId).contains(draft.id());
        assertThat(config.liveReferences()).extracting(PinnedReference::revisionId).doesNotContain(draft.id());

        publishThroughReview(config.findRevision(draft.id()).orElseThrow());
        assertThat(config.liveReferences()).extracting(PinnedReference::revisionId).contains(draft.id());
    }

    @Test
    void heartbeatUpsertAndClusterStatus() {
        long latest = publishThroughReview(newResource("{\"node\":1}")).generation();
        Instant started = clock.instant();
        config.heartbeat(new NodeHeartbeat("node-a", latest, "orders", "1.0", "0.1.0", started));
        config.heartbeat(new NodeHeartbeat("node-b", null, "orders", null, "0.1.0", started));
        clock.advance(Duration.ofSeconds(5));
        config.heartbeat(new NodeHeartbeat("node-b", latest, "orders", null, "0.1.0", started));

        ClusterStatus status = config.clusterStatus(Duration.ofSeconds(30));
        assertThat(status.latestGeneration()).isEqualTo(latest);
        assertThat(status.nodes()).extracting(ClusterStatus.Node::nodeId).contains("node-a", "node-b");
        assertThat(status.converged()).isTrue();

        clock.advance(Duration.ofMinutes(10));
        assertThat(config.clusterStatus(Duration.ofSeconds(30)).nodes()).noneMatch(ClusterStatus.Node::alive);
        assertThat(config.pruneNodes(Duration.ofMinutes(5))).isGreaterThanOrEqualTo(2);
    }
}
