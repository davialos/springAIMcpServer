package com.springaimcpservercommon.persistence.proposal;

import com.springaimcpservercommon.persistence.proposal.ProposalRuleViolationException.Reason;
import com.springaimcpservercommon.persistence.support.PageRequest;
import com.springaimcpservercommon.persistence.unit.DaiPersistenceUnit;
import com.springaimcpservercommon.persistence.unit.MutableClock;
import com.springaimcpservercommon.persistence.unit.PostgresTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proposal store against PostgreSQL: persistence of the aggregate, locking, idempotency, sweeps, DB guards. */
class ChangeProposalStoreIT {

    private static final MutableClock CLOCK = new MutableClock(Instant.now());
    private static DaiPersistenceUnit unit;
    private static ChangeProposalStore store;
    private static UUID workspace;

    @BeforeAll
    static void start() {
        unit = PostgresTestSupport.startFreshUnit();
        store = new ChangeProposalStore(unit, CLOCK, Duration.ofDays(7));
        workspace = PostgresTestSupport.workspace(unit.schema());
    }

    @AfterAll
    static void stop() {
        unit.close();
    }

    private static UUID principal() {
        return PostgresTestSupport.principal(unit.schema());
    }

    private static Reason reasonOf(Runnable action) {
        try {
            action.run();
        } catch (ProposalRuleViolationException e) {
            return e.reason();
        }
        throw new AssertionError("expected ProposalRuleViolationException");
    }

    @Test
    void persistsTheAggregateAndAppliesOwnerConfirmation() {
        UUID owner = principal();
        ChangeProposal created = store.create(ProposalFixtures.update(workspace, owner));

        ChangeProposal loaded = store.findForOwner(created.getId(), owner).orElseThrow();
        assertThat(loaded.getRecords()).singleElement().satisfies(r -> {
            assertThat(r.getEntityRef().toString()).isEqualTo("entity:com.acme.order.Order");
            assertThat(r.getBaseVersionKind()).isEqualTo(BaseVersionKind.JPA_VERSION);
        });
        assertThat(store.findForOwner(created.getId(), principal())).isEmpty();

        UUID stranger = principal();
        assertThat(reasonOf(() -> store.confirm(created.getId(), loaded.getRowVersion(), stranger, ProposalFixtures.HASH)))
                .isEqualTo(Reason.NOT_OWNER);

        ChangeProposal confirmed = store.confirm(created.getId(), loaded.getRowVersion(), owner, ProposalFixtures.HASH);
        assertThat(confirmed.getState()).isEqualTo(ProposalState.CONFIRMED);
        assertThat(confirmed.getRowVersion()).isGreaterThan(loaded.getRowVersion());
        assertThat(reasonOf(() -> store.confirm(created.getId(), loaded.getRowVersion(), owner, "sha256:" + "0".repeat(64))))
                .isEqualTo(Reason.STALE_VERSION);

        store.markApplying(created.getId());
        ChangeProposal applied = store.markApplied(created.getId(), "envers:42");
        assertThat(applied.getState()).isEqualTo(ProposalState.APPLIED);
        assertThat(applied.getRetentionUntil()).isAfterOrEqualTo(CLOCK.instant().plus(Duration.ofDays(7)).minusSeconds(1));
        assertThat(store.find(created.getId()).orElseThrow().getEvents())
                .extracting(ChangeProposalEvent::getSeq).containsExactly(0, 1, 2, 3);
    }

    @Test
    void createIsIdempotentPerOwnerAndKey() {
        UUID owner = principal();
        NewChangeProposal data = ProposalFixtures.update(workspace, owner, ApprovalRequirement.SELF_CONFIRM, 0, "key-1");

        ChangeProposal first = store.create(data);
        ChangeProposal again = store.create(data);
        assertThat(again.getId()).isEqualTo(first.getId());

        NewChangeProposal otherContent = new NewChangeProposal(data.workspaceId(), data.origin(), data.channel(), null,
                null, null, null, owner, data.targetKind(), data.targetRef(), null, data.changeKind(),
                data.approvalRequirement(), 0, "sha256:" + "a".repeat(64), "other", null, "key-1",
                data.timeToLive(), data.retention(), data.records());
        assertThat(reasonOf(() -> store.create(otherContent))).isEqualTo(Reason.IDEMPOTENCY_KEY_REUSED);

        // the same key of another owner is independent
        UUID other = principal();
        ChangeProposal othersProposal = store.create(ProposalFixtures.update(workspace, other,
                ApprovalRequirement.SELF_CONFIRM, 0, "key-1"));
        assertThat(othersProposal.getId()).isNotEqualTo(first.getId());
    }

    @Test
    void fourEyesFlowAndApprovalInbox() {
        UUID owner = principal();
        UUID approver = principal();
        ChangeProposal p = store.create(ProposalFixtures.update(workspace, owner,
                ApprovalRequirement.SELF_CONFIRM_PLUS_APPROVER, 1, null));
        store.confirm(p.getId(), null, owner, ProposalFixtures.HASH);

        assertThat(store.approvalInbox(approver, List.of(workspace), PageRequest.first(50)).items())
                .extracting(ProposalSummary::id).contains(p.getId());
        assertThat(store.approvalInbox(owner, List.of(workspace), PageRequest.first(50)).items())
                .extracting(ProposalSummary::id).doesNotContain(p.getId());
        assertThat(reasonOf(() -> store.approve(p.getId(), owner, null))).isEqualTo(Reason.OWNER_CANNOT_APPROVE);

        ChangeProposal approved = store.approve(p.getId(), approver, "looks right");
        assertThat(approved.getState()).isEqualTo(ProposalState.CONFIRMED);
        assertThat(approved.getApprovals()).singleElement()
                .satisfies(a -> assertThat(a.getApproverId()).isEqualTo(approver));
        assertThat(store.approvalInbox(approver, List.of(workspace), PageRequest.first(50)).items())
                .extracting(ProposalSummary::id).doesNotContain(p.getId());
    }

    @Test
    void databaseTriggerEnforcesSegregationOfDuties() {
        UUID owner = principal();
        ChangeProposal p = store.create(ProposalFixtures.update(workspace, owner));

        assertThatThrownBy(() -> PostgresTestSupport.jdbc().update("INSERT INTO " + unit.schema()
                        + ".dai_change_proposal_approval (proposal_id, approver_id, decision) VALUES (?, ?, 'APPROVED')",
                p.getId(), owner))
                .hasRootCauseInstanceOf(SQLException.class)
                .rootCause().satisfies(e -> {
                    assertThat(((SQLException) e).getSQLState()).isEqualTo("23514");
                    assertThat(e.getMessage()).contains("segregation of duties");
                });
    }

    @Test
    void databaseRejectsConfirmationByAnotherPrincipal() {
        UUID owner = principal();
        ChangeProposal p = store.create(ProposalFixtures.update(workspace, owner));

        assertThatThrownBy(() -> PostgresTestSupport.jdbc().update("UPDATE " + unit.schema()
                        + ".dai_change_proposal SET confirmed_by = ?, confirmed_at = now() WHERE id = ?",
                principal(), p.getId()))
                .hasRootCauseInstanceOf(SQLException.class);
    }

    @Test
    void expireDueExpiresOnlyOverdueProposals() {
        UUID owner = principal();
        ChangeProposal due = store.create(ProposalFixtures.update(workspace, owner));
        CLOCK.advance(Duration.ofMinutes(16));
        ChangeProposal fresh = store.create(ProposalFixtures.update(workspace, owner));

        int expired = store.expireDue(100);

        assertThat(expired).isGreaterThanOrEqualTo(1);
        assertThat(store.find(due.getId()).orElseThrow().getState()).isEqualTo(ProposalState.EXPIRED);
        assertThat(store.find(fresh.getId()).orElseThrow().getState()).isEqualTo(ProposalState.PROPOSED);
        assertThat(store.pendingOf(owner, PageRequest.first(10)).items())
                .extracting(ProposalSummary::id).containsExactly(fresh.getId());
    }

    @Test
    void applyingProposalsAreFoundForReconciliation() {
        UUID owner = principal();
        ChangeProposal p = store.create(ProposalFixtures.update(workspace, owner));
        store.confirm(p.getId(), null, owner, ProposalFixtures.HASH);
        store.markApplying(p.getId());
        CLOCK.advance(Duration.ofMinutes(10));

        assertThat(store.applyingSince(Duration.ofMinutes(5), 100)).contains(p.getId());
        assertThat(store.applyingSince(Duration.ofHours(1), 100)).doesNotContain(p.getId());
    }
}
