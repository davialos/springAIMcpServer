package com.springaimcpservercommon.persistence.proposal;

import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.hash.Sha256;
import com.springaimcpservercommon.persistence.proposal.ProposalRuleViolationException.Reason;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChangeProposalTest {

    private static final Instant T0 = Instant.parse("2026-09-28T10:00:00Z");
    private final UUID workspace = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final UUID approver = UUID.randomUUID();
    private final UUID approver2 = UUID.randomUUID();

    private ChangeProposal selfConfirm() {
        return ChangeProposal.propose(ProposalFixtures.update(workspace, owner), T0);
    }

    private ChangeProposal fourEyes(int approvals) {
        return ChangeProposal.propose(ProposalFixtures.update(workspace, owner,
                ApprovalRequirement.SELF_CONFIRM_PLUS_APPROVER, approvals, null), T0);
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
    void proposeCreatesProposedStateWithCreatingEvent() {
        ChangeProposal p = selfConfirm();

        assertThat(p.getState()).isEqualTo(ProposalState.PROPOSED);
        assertThat(p.getExpiresAt()).isEqualTo(T0.plus(Duration.ofMinutes(15)));
        assertThat(p.getRetentionUntil()).isEqualTo(T0.plus(Duration.ofMinutes(15)).plus(Duration.ofDays(7)));
        assertThat(p.getRecords()).hasSize(1);
        assertThat(p.getRecords().getFirst().getAfterValuesJson()).isEqualTo("{\"status\":\"SHIPPED\"}");
        assertThat(p.getEvents()).singleElement().satisfies(e -> {
            assertThat(e.getSeq()).isZero();
            assertThat(e.getFromState()).isNull();
            assertThat(e.getToState()).isEqualTo(ProposalState.PROPOSED);
            assertThat(e.getActorId()).isEqualTo(owner);
        });
        assertThat(p.getInitialContentHash()).isEqualTo(ProposalFixtures.HASH);
    }

    @Test
    void selfConfirmHappyPathThroughApplied() {
        ChangeProposal p = selfConfirm();

        assertThat(p.confirm(owner, ProposalFixtures.HASH, T0.plusSeconds(60))).isTrue();
        assertThat(p.getState()).isEqualTo(ProposalState.CONFIRMED);
        assertThat(p.getConfirmedBy()).isEqualTo(owner);
        p.markApplying(T0.plusSeconds(61));
        p.markApplied("envers:5813", T0.plusSeconds(62));

        assertThat(p.getState()).isEqualTo(ProposalState.APPLIED);
        assertThat(p.getHostRevisionRef()).isEqualTo("envers:5813");
        assertThat(p.getAppliedAt()).isEqualTo(T0.plusSeconds(62));
        assertThat(p.getEvents()).extracting(ChangeProposalEvent::getSeq).containsExactly(0, 1, 2, 3);
        assertThat(p.getEvents()).extracting(ChangeProposalEvent::getToState).containsExactly(ProposalState.PROPOSED,
                ProposalState.CONFIRMED, ProposalState.APPLYING, ProposalState.APPLIED);
    }

    @Test
    void onlyTheOwnerMayConfirmEditOrDecline() {
        ChangeProposal p = selfConfirm();
        UUID stranger = UUID.randomUUID();

        assertThat(reasonOf(() -> p.confirm(stranger, ProposalFixtures.HASH, T0))).isEqualTo(Reason.NOT_OWNER);
        assertThat(reasonOf(() -> p.edit(stranger, List.of(new RecordEdit(0, "{}")), Sha256.of("x"), null, T0)))
                .isEqualTo(Reason.NOT_OWNER);
        assertThat(reasonOf(() -> p.decline(stranger, null, T0))).isEqualTo(Reason.NOT_OWNER);
        assertThat(p.getState()).isEqualTo(ProposalState.PROPOSED);
    }

    @Test
    void confirmRequiresTheCurrentContentHash() {
        ChangeProposal p = selfConfirm();
        String edited = Sha256.of("content-v2");
        p.edit(owner, List.of(new RecordEdit(0, "{ \"status\" : \"CANCELLED\" }")), edited, "{\"errors\":[]}", T0);

        assertThat(p.getState()).isEqualTo(ProposalState.EDITED);
        assertThat(p.getRecords().getFirst().getAfterValuesJson()).isEqualTo("{\"status\":\"CANCELLED\"}");
        assertThat(reasonOf(() -> p.confirm(owner, ProposalFixtures.HASH, T0))).isEqualTo(Reason.CONTENT_HASH_MISMATCH);
        assertThat(p.confirm(owner, edited, T0)).isTrue();
        assertThat(p.getState()).isEqualTo(ProposalState.CONFIRMED);
        assertThat(p.getInitialContentHash()).isEqualTo(ProposalFixtures.HASH);
    }

    @Test
    void repeatedIdenticalConfirmIsANoOp() {
        ChangeProposal p = selfConfirm();
        p.confirm(owner, ProposalFixtures.HASH, T0);

        assertThat(p.confirm(owner, ProposalFixtures.HASH, T0.plusSeconds(1))).isFalse();
        assertThat(p.getEvents()).hasSize(2);
    }

    @Test
    void expiredProposalCannotBeConfirmedAndExpires() {
        ChangeProposal p = selfConfirm();
        Instant late = p.getExpiresAt();

        assertThat(reasonOf(() -> p.confirm(owner, ProposalFixtures.HASH, late))).isEqualTo(Reason.EXPIRED);
        assertThat(reasonOf(() -> p.expire(late.minusSeconds(1)))).isEqualTo(Reason.ILLEGAL_TRANSITION);
        p.expire(late);
        assertThat(p.getState()).isEqualTo(ProposalState.EXPIRED);
        assertThat(p.getEvents().getLast().getActorId()).isNull();
        assertThat(p.getState().isTerminal()).isTrue();
    }

    @Test
    void fourEyesNeedsApproversOtherThanTheOwner() {
        ChangeProposal p = fourEyes(2);
        p.confirm(owner, ProposalFixtures.HASH, T0);
        assertThat(p.getState()).isEqualTo(ProposalState.AWAITING_APPROVAL);
        assertThat(p.getConfirmedAt()).isEqualTo(T0);

        assertThat(reasonOf(() -> p.approve(owner, null, T0))).isEqualTo(Reason.OWNER_CANNOT_APPROVE);
        assertThat(p.approve(approver, "ok", T0.plusSeconds(1))).isFalse();
        assertThat(p.getState()).isEqualTo(ProposalState.AWAITING_APPROVAL);
        assertThat(reasonOf(() -> p.approve(approver, null, T0.plusSeconds(2)))).isEqualTo(Reason.ALREADY_DECIDED);
        assertThat(p.approve(approver2, null, T0.plusSeconds(3))).isTrue();

        assertThat(p.getState()).isEqualTo(ProposalState.CONFIRMED);
        assertThat(p.getApprovals()).extracting(ChangeProposalApproval::getApproverId).containsExactly(approver, approver2);
        assertThat(p.getEvents()).extracting(ChangeProposalEvent::getToState).containsExactly(ProposalState.PROPOSED,
                ProposalState.AWAITING_APPROVAL, ProposalState.CONFIRMED);
    }

    @Test
    void approverRejectionNeedsCommentAndEndsTheProposal() {
        ChangeProposal p = fourEyes(1);
        p.confirm(owner, ProposalFixtures.HASH, T0);

        assertThat(reasonOf(() -> p.reject(owner, "no", T0))).isEqualTo(Reason.OWNER_CANNOT_APPROVE);
        assertThatThrownBy(() -> p.reject(approver, " ", T0)).isInstanceOf(IllegalArgumentException.class);
        p.reject(approver, "Customer on credit hold", T0);

        assertThat(p.getState()).isEqualTo(ProposalState.REJECTED);
        assertThat(p.getApprovals()).singleElement()
                .satisfies(a -> assertThat(a.getDecision()).isEqualTo(ApprovalDecision.REJECTED));
    }

    @Test
    void requireApprovalEscalatesButNeverLowers() {
        ChangeProposal p = selfConfirm();
        p.requireApproval(2, null, T0);
        p.requireApproval(1, null, T0);

        assertThat(p.getApprovalRequirement()).isEqualTo(ApprovalRequirement.SELF_CONFIRM_PLUS_APPROVER);
        assertThat(p.getRequiredApprovals()).isEqualTo(2);
        assertThat(p.getEvents()).hasSize(2);
        assertThat(p.getEvents().getLast().getFromState()).isEqualTo(ProposalState.PROPOSED);
        assertThat(p.getEvents().getLast().getToState()).isEqualTo(ProposalState.PROPOSED);
        p.confirm(owner, ProposalFixtures.HASH, T0);
        assertThat(p.getState()).isEqualTo(ProposalState.AWAITING_APPROVAL);
    }

    @Test
    void applyOutcomesAreOnlyReachableAfterConfirmation() {
        ChangeProposal p = selfConfirm();

        assertThat(reasonOf(() -> p.markApplying(T0))).isEqualTo(Reason.ILLEGAL_TRANSITION);
        assertThat(reasonOf(() -> p.markApplied(null, T0))).isEqualTo(Reason.ILLEGAL_TRANSITION);
        assertThat(reasonOf(() -> p.markFailed("X", null, T0))).isEqualTo(Reason.ILLEGAL_TRANSITION);

        p.confirm(owner, ProposalFixtures.HASH, T0);
        p.markApplying(T0);
        p.markConflict("VERSION_MISMATCH", "row changed", T0);
        assertThat(p.getState()).isEqualTo(ProposalState.CONFLICT);
        assertThat(p.getFailureCode()).isEqualTo("VERSION_MISMATCH");
        assertThat(reasonOf(() -> p.markApplied(null, T0))).isEqualTo(Reason.ILLEGAL_TRANSITION);
        assertThat(reasonOf(() -> p.decline(owner, null, T0))).isEqualTo(Reason.ILLEGAL_TRANSITION);
    }

    @Test
    void ownerMayDeclineWhileAwaitingApproval() {
        ChangeProposal p = fourEyes(1);
        p.confirm(owner, ProposalFixtures.HASH, T0);
        p.decline(owner, "CHANGED_MIND", T0);

        assertThat(p.getState()).isEqualTo(ProposalState.REJECTED);
        assertThat(p.getEvents().getLast().getDetailsJson()).isEqualTo("{\"declinedByOwner\":true,\"reason\":\"CHANGED_MIND\"}");
    }

    @Test
    void factoryMirrorsCheckConstraints() {
        NewChangeProposal valid = ProposalFixtures.update(workspace, owner);

        assertThatThrownBy(() -> ChangeProposal.propose(withTarget(valid, CatalogElementRef.entity("com.acme.Order")), T0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("OP");
        assertThatThrownBy(() -> ChangeProposal.propose(ProposalFixtures.update(workspace, owner,
                ApprovalRequirement.SELF_CONFIRM_PLUS_APPROVER, 0, null), T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChangeProposal.propose(ProposalFixtures.update(workspace, owner,
                ApprovalRequirement.SELF_CONFIRM_PLUS_APPROVER, 6, null), T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChangeProposal.propose(withRecords(valid, List.of(new NewProposalRecord(
                CatalogElementRef.entity("com.acme.Order"), "1", null, null, null, null))), T0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("before or after");
        assertThatThrownBy(() -> ChangeProposal.propose(withRecords(valid, List.of(new NewProposalRecord(
                CatalogElementRef.entity("com.acme.Order"), "1", null, "{}", BaseVersionKind.JPA_VERSION, null))), T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static NewChangeProposal withTarget(NewChangeProposal d, CatalogElementRef target) {
        return new NewChangeProposal(d.workspaceId(), d.origin(), d.channel(), d.conversationId(), d.turnId(),
                d.toolInvocationId(), d.mcpSessionId(), d.ownerId(), d.targetKind(), target, d.targetArgsJson(),
                d.changeKind(), d.approvalRequirement(), d.requiredApprovals(), d.contentHash(), d.summary(),
                d.validationJson(), d.idempotencyKey(), d.timeToLive(), d.retention(), d.records());
    }

    private static NewChangeProposal withRecords(NewChangeProposal d, List<NewProposalRecord> records) {
        return new NewChangeProposal(d.workspaceId(), d.origin(), d.channel(), d.conversationId(), d.turnId(),
                d.toolInvocationId(), d.mcpSessionId(), d.ownerId(), d.targetKind(), d.targetRef(), d.targetArgsJson(),
                d.changeKind(), d.approvalRequirement(), d.requiredApprovals(), d.contentHash(), d.summary(),
                d.validationJson(), d.idempotencyKey(), d.timeToLive(), d.retention(), records);
    }
}
