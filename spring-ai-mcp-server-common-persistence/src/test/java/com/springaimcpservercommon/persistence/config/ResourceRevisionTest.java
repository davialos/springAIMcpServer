package com.springaimcpservercommon.persistence.config;

import com.springaimcpservercommon.core.id.Ids;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceRevisionTest {

    private static final Instant T0 = Instant.parse("2026-09-28T10:00:00Z");
    private final UUID author = Ids.newId();
    private final UUID reviewer = Ids.newId();
    private final Resource resource = Resource.create(Ids.newId(), ResourceKind.QUERY, "recent-orders", author, T0);

    private ResourceRevision draft() {
        return ResourceRevision.draft(resource, 1, DraftContent.of("{\"b\":2,\"a\":1}"), null, author, T0);
    }

    @Test
    void draftCanonicalisesAndHashesTheSpec() {
        ResourceRevision revision = draft();

        assertThat(revision.getState()).isEqualTo(RevisionState.DRAFT);
        assertThat(revision.getSpec()).isEqualTo("{\"a\":1,\"b\":2}");
        assertThat(revision.getSpecHash()).isEqualTo(CanonicalSpec.of("{\"a\":1,\"b\":2}").hash());
        assertThat(revision.specHashMatches()).isTrue();
    }

    @Test
    void onlyTheAuthorCanEditAndOnlyWhileDraft() {
        ResourceRevision revision = draft();

        revision.editDraft(author, DraftContent.of("{\"a\":3}"));
        assertThat(revision.getSpec()).isEqualTo("{\"a\":3}");

        assertThatThrownBy(() -> revision.editDraft(reviewer, DraftContent.of("{\"a\":4}")))
                .isInstanceOf(ConfigLifecycleException.class);

        revision.submit(20, T0);
        assertThatThrownBy(() -> revision.editDraft(author, DraftContent.of("{\"a\":5}")))
                .isInstanceOf(ConfigLifecycleException.class);
        assertThat(revision.getSpec()).isEqualTo("{\"a\":3}");
    }

    @Test
    void walksTheHappyPathThroughPublishSupersedeAndRollback() {
        ResourceRevision revision = draft();
        revision.submit(null, T0);
        revision.approve(T0.plusSeconds(1));
        revision.publish(T0.plusSeconds(2));
        assertThat(revision.getState()).isEqualTo(RevisionState.PUBLISHED);
        assertThat(revision.getPublishedAt()).isEqualTo(T0.plusSeconds(2));

        revision.supersede();
        assertThat(revision.getState()).isEqualTo(RevisionState.SUPERSEDED);

        revision.publish(T0.plusSeconds(3)); // rollback republish
        revision.deprecate();
        revision.retire(T0.plusSeconds(4));
        assertThat(revision.getState()).isEqualTo(RevisionState.RETIRED);
    }

    @Test
    void cannotPublishWithoutApproval() {
        ResourceRevision revision = draft();
        assertThatThrownBy(() -> revision.publish(T0)).isInstanceOf(ConfigLifecycleException.class);
        revision.submit(null, T0);
        assertThatThrownBy(() -> revision.publish(T0)).isInstanceOf(ConfigLifecycleException.class);
    }

    @Test
    void rejectsRiskScoreOutOfRange() {
        assertThatThrownBy(() -> draft().submit(101, T0)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(RevisionState.class)
    void noStateLeadsBackToDraft(RevisionState from) {
        assertThat(from.canTransitionTo(RevisionState.DRAFT)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = RevisionState.class, names = {"REJECTED", "STALE", "RETIRED"})
    void terminalStatesHaveNoSuccessor(RevisionState terminal) {
        for (RevisionState target : RevisionState.values()) {
            assertThat(terminal.canTransitionTo(target)).isFalse();
        }
    }

    @Test
    void transitionTableMatchesLld09() {
        assertThat(successors(RevisionState.DRAFT)).containsExactly(RevisionState.IN_REVIEW);
        assertThat(successors(RevisionState.IN_REVIEW))
                .containsExactlyInAnyOrder(RevisionState.APPROVED, RevisionState.REJECTED, RevisionState.STALE);
        assertThat(successors(RevisionState.APPROVED))
                .containsExactlyInAnyOrder(RevisionState.PUBLISHED, RevisionState.STALE);
        assertThat(successors(RevisionState.PUBLISHED))
                .containsExactlyInAnyOrder(RevisionState.SUPERSEDED, RevisionState.DEPRECATED);
        assertThat(successors(RevisionState.DEPRECATED))
                .containsExactlyInAnyOrder(RevisionState.SUPERSEDED, RevisionState.RETIRED);
        assertThat(successors(RevisionState.SUPERSEDED)).containsExactly(RevisionState.PUBLISHED);
        assertThat(RevisionState.liveStates()).containsExactlyInAnyOrder(RevisionState.PUBLISHED, RevisionState.DEPRECATED);
    }

    private static Set<RevisionState> successors(RevisionState from) {
        Set<RevisionState> result = EnumSet.noneOf(RevisionState.class);
        for (RevisionState target : RevisionState.values()) {
            if (from.canTransitionTo(target)) {
                result.add(target);
            }
        }
        return result;
    }

    @Test
    void reviewEnforcesSegregationOfDuties() {
        ResourceRevision revision = draft();
        revision.submit(null, T0);

        assertThatThrownBy(() -> Review.record(revision, author, ReviewDecision.APPROVED, null, T0))
                .isInstanceOf(SegregationOfDutiesException.class);
        assertThat(Review.record(revision, reviewer, ReviewDecision.APPROVED, null, T0).getDecision())
                .isEqualTo(ReviewDecision.APPROVED);
    }

    @Test
    void reviewNeedsInReviewStateAndCommentForRejection() {
        ResourceRevision revision = draft();
        assertThatThrownBy(() -> Review.record(revision, reviewer, ReviewDecision.APPROVED, null, T0))
                .isInstanceOf(ConfigLifecycleException.class);

        revision.submit(null, T0);
        assertThatThrownBy(() -> Review.record(revision, reviewer, ReviewDecision.REJECTED, " ", T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retiredResourceCannotGetNewDrafts() {
        resource.retire(author, T0);
        assertThatThrownBy(this::draft).isInstanceOf(ConfigLifecycleException.class);
    }
}
