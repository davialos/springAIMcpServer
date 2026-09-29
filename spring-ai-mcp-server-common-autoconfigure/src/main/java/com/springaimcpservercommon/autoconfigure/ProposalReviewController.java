package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.hash.Sha256;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.persistence.audit.AuditCategory;
import com.springaimcpservercommon.persistence.audit.AuditPlane;
import com.springaimcpservercommon.persistence.proposal.ChangeProposal;
import com.springaimcpservercommon.persistence.proposal.ChangeProposalApproval;
import com.springaimcpservercommon.persistence.proposal.ChangeProposalEvent;
import com.springaimcpservercommon.persistence.proposal.ChangeProposalRecord;
import com.springaimcpservercommon.persistence.proposal.ChangeProposalStore;
import com.springaimcpservercommon.persistence.proposal.ProposalSummary;
import com.springaimcpservercommon.persistence.support.PageRequest;
import com.springaimcpservercommon.persistence.support.Slice;
import com.springaimcpservercommon.security.permission.Permission;
import com.springaimcpservercommon.webmvc.problem.ProblemCode;
import com.springaimcpservercommon.webmvc.problem.ProblemDetailFactory;
import com.springaimcpservercommon.webmvc.problem.ProblemDetailFactory.FieldViolation;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Review API for change proposals (LLD-11 §8, F-45): list, view, confirm, decline, and second-person
 * approve/reject. Only reviewed, user-confirmed proposals can reach the write executor; a model can never
 * call these endpoints (they need an authenticated user request).
 *
 * <p>Visibility: the owner and workspace approvers can view a proposal; everyone else gets 404. Confirm and
 * decline are owner-only. Approve and reject need {@link Permission#DATA_WRITE_APPROVE} in the proposal's
 * workspace and the owner cannot approve their own proposal (enforced by the store).
 *
 * <p>Scope of this slice: editing a proposal (PATCH) and applying a confirmed proposal through the write
 * executor are not included; see {@code docs/open-questions.md} OQ-36.
 *
 * <p>Not a {@code @Component}; registered by {@link DaiAdminAutoConfiguration}.
 */
@NullMarked
@RequestMapping("/dynamic-ai/api/proposals")
public final class ProposalReviewController {

    static final int MAX_COMMENT_LENGTH = 1000;
    static final int MAX_REASON_LENGTH = 200;

    /**
     * Proposal row in a list.
     *
     * @param id                id
     * @param workspaceId       workspace
     * @param ownerId           owner
     * @param targetRef         catalog element the change targets
     * @param changeKind        CREATE, UPDATE, DELETE or BULK
     * @param state             lifecycle state
     * @param summary           human summary
     * @param requiredApprovals second-person approvals required
     * @param createdAt         creation time
     * @param expiresAt         expiry time
     * @param version           row version (also the ETag)
     */
    public record SummaryView(UUID id, UUID workspaceId, UUID ownerId, String targetRef, String changeKind,
                              String state, String summary, int requiredApprovals, Instant createdAt,
                              Instant expiresAt, long version) {
        static SummaryView of(ProposalSummary s) {
            return new SummaryView(s.id(), s.workspaceId(), s.ownerId(), s.targetRef().toString(),
                    s.changeKind().name(), s.state().name(), s.summary(), s.requiredApprovals(), s.createdAt(),
                    s.expiresAt(), s.rowVersion());
        }
    }

    /**
     * One page of proposals.
     *
     * @param items   rows
     * @param limit   page size
     * @param offset  page offset
     * @param hasMore whether another page exists
     */
    public record PageView(List<SummaryView> items, int limit, int offset, boolean hasMore) {
        static PageView of(Slice<ProposalSummary> slice) {
            return new PageView(slice.items().stream().map(SummaryView::of).toList(), slice.page().limit(),
                    slice.page().offset(), slice.hasMore());
        }
    }

    /**
     * Full proposal for the review UI. Before/after values are the stored, already-masked snapshots.
     *
     * @param summary         list fields
     * @param contentHash     hash the user must echo when confirming
     * @param approval        SELF_CONFIRM or SELF_CONFIRM_PLUS_APPROVER
     * @param targetArgsJson  arguments of a host-operation target, if any
     * @param validationJson  validation report, if any
     * @param confirmedAt     when confirmed
     * @param confirmedBy     who confirmed
     * @param appliedAt       when applied
     * @param hostRevisionRef host revision created by the apply, if known
     * @param failureCode     failure code, if failed
     * @param failureMessage  sanitised failure message, if any
     * @param records         record changes
     * @param approvals       second-person decisions
     * @param events          state history
     * @param canConfirm      whether this caller may confirm now
     * @param canDecide       whether this caller may approve or reject now
     */
    public record DetailView(SummaryView summary, String contentHash, String approval,
                             @Nullable String targetArgsJson, @Nullable String validationJson,
                             @Nullable Instant confirmedAt, @Nullable UUID confirmedBy,
                             @Nullable Instant appliedAt, @Nullable String hostRevisionRef,
                             @Nullable String failureCode, @Nullable String failureMessage,
                             List<RecordView> records, List<ApprovalView> approvals, List<EventView> events,
                             boolean canConfirm, boolean canDecide) {}

    /**
     * One record change.
     *
     * @param seq                position
     * @param entityRef          entity ref
     * @param entityId           row id, null for CREATE
     * @param beforeValuesJson   masked snapshot before
     * @param afterValuesJson    proposed values
     * @param baseVersionKind    version token kind
     * @param baseVersionValue   version token value
     */
    public record RecordView(int seq, String entityRef, @Nullable String entityId,
                             @Nullable String beforeValuesJson, @Nullable String afterValuesJson,
                             @Nullable String baseVersionKind, @Nullable String baseVersionValue) {}

    /**
     * One approval decision.
     *
     * @param approverId approver
     * @param decision   APPROVED or REJECTED
     * @param comment    comment
     * @param decidedAt  time
     */
    public record ApprovalView(UUID approverId, String decision, @Nullable String comment, Instant decidedAt) {}

    /**
     * One state transition.
     *
     * @param seq        position
     * @param fromState  previous state
     * @param toState    new state
     * @param actorId    actor, null for system
     * @param occurredAt time
     */
    public record EventView(int seq, @Nullable String fromState, String toState, @Nullable UUID actorId,
                            Instant occurredAt) {}

    /**
     * Confirm request.
     *
     * @param contentHash the {@code sha256:} hash of the content the user reviewed
     */
    public record ConfirmRequest(@Nullable String contentHash) {}

    /**
     * Approve, reject or decline request.
     *
     * @param comment approver comment (mandatory for reject); reason text for decline
     */
    public record DecisionRequest(@Nullable String comment) {}

    private final ChangeProposalStore store;
    private final AdminAudit audit;
    private final AdminApi api;

    ProposalReviewController(ChangeProposalStore store, AdminAudit audit, AdminApi api) {
        this.store = Objects.requireNonNull(store, "store");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.api = Objects.requireNonNull(api, "api");
    }

    /**
     * Lists proposals.
     *
     * @param scope   {@code mine} (default: caller's pending proposals) or {@code inbox} (proposals awaiting
     *                approval in workspaces where the caller may approve)
     * @param limit   page size (1..200, default 50)
     * @param offset  page offset
     * @param request current request
     * @return 200 with a page
     */
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) @Nullable String scope,
                                  @RequestParam(required = false) @Nullable Integer limit,
                                  @RequestParam(required = false) @Nullable Integer offset,
                                  HttpServletRequest request) {
        var gate = api.authenticated(request);
        if (!gate.open()) {
            return gate.denied();
        }
        DaiPrincipal caller = gate.caller();
        PageRequest page = AdminApi.page(limit, offset);
        String mode = scope == null || scope.isBlank() ? "mine" : scope.strip().toLowerCase(Locale.ROOT);
        switch (mode) {
            case "mine" -> {
                return ResponseEntity.ok(PageView.of(store.pendingOf(caller.principalId(), page)));
            }
            case "inbox" -> {
                Set<UUID> workspaces = caller.workspaceRoles().keySet().stream()
                        .filter(ws -> api.permits(caller, Permission.DATA_WRITE_APPROVE, ws))
                        .collect(Collectors.toSet());
                if (workspaces.isEmpty()) {
                    return ResponseEntity.ok(new PageView(List.of(), page.limit(), page.offset(), false));
                }
                return ResponseEntity.ok(PageView.of(store.approvalInbox(caller.principalId(), workspaces, page)));
            }
            default -> {
                return ResponseEntity.status(ProblemCode.INVALID_ARGUMENT.httpStatus())
                        .contentType(AdminApi.PROBLEM_JSON)
                        .body(ProblemDetailFactory.buildValidation(request.getRequestURI(),
                                List.of(new FieldViolation("scope", "must be mine or inbox"))));
            }
        }
    }

    /**
     * Returns one proposal with its records, approvals and history.
     *
     * @param id      proposal id
     * @param request current request
     * @return 200 with detail and an {@code ETag}; 404 when unknown or not visible to the caller
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable UUID id, HttpServletRequest request) {
        var gate = api.authenticated(request);
        if (!gate.open()) {
            return gate.denied();
        }
        DaiPrincipal caller = gate.caller();
        ChangeProposal proposal = store.find(id).orElse(null);
        if (proposal == null || !visibleTo(caller, proposal)) {
            return AdminApi.problem(ProblemCode.NOT_FOUND, "Proposal not found", null, request);
        }
        return ok(proposal, caller);
    }

    /**
     * Confirms a proposal as its owner. The caller must echo the content hash they reviewed; a changed
     * proposal answers 409 and must be reloaded. Repeating an identical confirm is idempotent.
     *
     * @param id      proposal id
     * @param ifMatch optional row version from the last read
     * @param body    the reviewed content hash
     * @param request current request
     * @return 200 with the proposal (CONFIRMED or AWAITING_APPROVAL)
     */
    @PostMapping("/{id:[^:]+}:confirm")
    public ResponseEntity<?> confirm(@PathVariable UUID id,
                                     @RequestHeader(value = "If-Match", required = false) @Nullable String ifMatch,
                                     @RequestBody ConfirmRequest body, HttpServletRequest request) {
        var gate = api.authenticated(request);
        if (!gate.open()) {
            return gate.denied();
        }
        DaiPrincipal caller = gate.caller();
        ChangeProposal existing = store.find(id).orElse(null);
        if (existing == null || !existing.getOwnerId().equals(caller.principalId())) {
            return AdminApi.problem(ProblemCode.NOT_FOUND, "Proposal not found", null, request);
        }
        if (!api.permits(caller, Permission.DATA_WRITE_CONFIRM, existing.getWorkspaceId())) {
            return AdminApi.problem(ProblemCode.ACCESS_DENIED, "Access denied", null, request);
        }
        String hash = body.contentHash();
        if (hash == null || !Sha256.isValid(hash)) {
            return validation(request, "contentHash", "is required and must be sha256:<64 lowercase hex>");
        }
        Long version = AdminApi.ifMatch(ifMatch);
        ChangeProposal confirmed = store.confirm(id, version, caller.principalId(), hash);
        audit(caller, "PROPOSAL_CONFIRMED", confirmed, null);
        return ok(confirmed, caller);
    }

    /**
     * Second-person approval.
     *
     * @param id      proposal id
     * @param body    optional comment (max 1000 characters)
     * @param request current request
     * @return 200 with the proposal; 403 for the owner; 404 when not visible
     */
    @PostMapping("/{id:[^:]+}:approve")
    public ResponseEntity<?> approve(@PathVariable UUID id, @RequestBody(required = false) @Nullable DecisionRequest body,
                                     HttpServletRequest request) {
        return decide(id, body, request, true);
    }

    /**
     * Second-person rejection (comment mandatory).
     *
     * @param id      proposal id
     * @param body    comment (1..1000 characters)
     * @param request current request
     * @return 200 with the proposal (REJECTED)
     */
    @PostMapping("/{id:[^:]+}:reject")
    public ResponseEntity<?> reject(@PathVariable UUID id, @RequestBody @Nullable DecisionRequest body,
                                    HttpServletRequest request) {
        return decide(id, body, request, false);
    }

    /**
     * Owner withdraws the proposal.
     *
     * @param id      proposal id
     * @param body    optional reason (max 200 characters)
     * @param request current request
     * @return 200 with the proposal
     */
    @PostMapping("/{id:[^:]+}:decline")
    public ResponseEntity<?> decline(@PathVariable UUID id, @RequestBody(required = false) @Nullable DecisionRequest body,
                                     HttpServletRequest request) {
        var gate = api.authenticated(request);
        if (!gate.open()) {
            return gate.denied();
        }
        DaiPrincipal caller = gate.caller();
        ChangeProposal existing = store.find(id).orElse(null);
        if (existing == null || !existing.getOwnerId().equals(caller.principalId())) {
            return AdminApi.problem(ProblemCode.NOT_FOUND, "Proposal not found", null, request);
        }
        String reason = body == null || body.comment() == null ? null : body.comment().strip();
        if (reason != null && reason.length() > MAX_REASON_LENGTH) {
            return validation(request, "comment", "must be at most " + MAX_REASON_LENGTH + " characters");
        }
        ChangeProposal declined = store.decline(id, caller.principalId(), reason == null || reason.isEmpty() ? null : reason);
        audit(caller, "PROPOSAL_DECLINED", declined, reason);
        return ok(declined, caller);
    }

    private ResponseEntity<?> decide(UUID id, @Nullable DecisionRequest body, HttpServletRequest request,
                                     boolean approve) {
        var gate = api.authenticated(request);
        if (!gate.open()) {
            return gate.denied();
        }
        DaiPrincipal caller = gate.caller();
        ChangeProposal existing = store.find(id).orElse(null);
        if (existing == null || !visibleTo(caller, existing)) {
            return AdminApi.problem(ProblemCode.NOT_FOUND, "Proposal not found", null, request);
        }
        if (!api.permits(caller, Permission.DATA_WRITE_APPROVE, existing.getWorkspaceId())) {
            return AdminApi.problem(ProblemCode.ACCESS_DENIED, "Access denied", null, request);
        }
        String comment = body == null || body.comment() == null ? null : body.comment().strip();
        if (comment != null && comment.length() > MAX_COMMENT_LENGTH) {
            return validation(request, "comment", "must be at most " + MAX_COMMENT_LENGTH + " characters");
        }
        if (!approve && (comment == null || comment.isEmpty())) {
            return validation(request, "comment", "is required when rejecting");
        }
        String normalized = comment == null || comment.isEmpty() ? null : comment;
        ChangeProposal result = approve
                ? store.approve(id, caller.principalId(), normalized)
                : store.reject(id, caller.principalId(), Objects.requireNonNull(normalized));
        audit(caller, approve ? "PROPOSAL_APPROVED" : "PROPOSAL_REJECTED", result, normalized);
        return ok(result, caller);
    }

    private boolean visibleTo(DaiPrincipal caller, ChangeProposal proposal) {
        return proposal.getOwnerId().equals(caller.principalId())
                || api.permits(caller, Permission.DATA_WRITE_APPROVE, proposal.getWorkspaceId());
    }

    private ResponseEntity<DetailView> ok(ChangeProposal p, DaiPrincipal caller) {
        boolean owner = p.getOwnerId().equals(caller.principalId());
        boolean pending = p.getState().isPending();
        boolean canConfirm = owner && pending && api.permits(caller, Permission.DATA_WRITE_CONFIRM, p.getWorkspaceId());
        boolean canDecide = !owner && p.getState() == com.springaimcpservercommon.persistence.proposal.ProposalState.AWAITING_APPROVAL
                && api.permits(caller, Permission.DATA_WRITE_APPROVE, p.getWorkspaceId());
        DetailView view = new DetailView(SummaryView.of(ProposalSummary.of(p)), p.getContentHash(),
                p.getApprovalRequirement().name(), p.getTargetArgsJson(), p.getValidationJson(), p.getConfirmedAt(),
                p.getConfirmedBy(), p.getAppliedAt(), p.getHostRevisionRef(), p.getFailureCode(),
                p.getFailureMessage(), p.getRecords().stream().map(ProposalReviewController::recordView).toList(),
                p.getApprovals().stream().map(ProposalReviewController::approval).toList(),
                p.getEvents().stream().map(ProposalReviewController::event).toList(), canConfirm, canDecide);
        return ResponseEntity.ok().eTag("\"" + p.getRowVersion() + "\"").body(view);
    }

    private static RecordView recordView(ChangeProposalRecord r) {
        return new RecordView(r.getSeq(), r.getEntityRef().toString(), r.getEntityId(), r.getBeforeValuesJson(),
                r.getAfterValuesJson(), r.getBaseVersionKind() == null ? null : r.getBaseVersionKind().name(),
                r.getBaseVersionValue());
    }

    private static ApprovalView approval(ChangeProposalApproval a) {
        return new ApprovalView(a.getApproverId(), a.getDecision().name(), a.getComment(), a.getDecidedAt());
    }

    private static EventView event(ChangeProposalEvent e) {
        return new EventView(e.getSeq(), e.getFromState() == null ? null : e.getFromState().name(),
                e.getToState().name(), e.getActorId(), e.getOccurredAt());
    }

    private void audit(DaiPrincipal caller, String action, ChangeProposal p, @Nullable String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("state", p.getState().name());
        audit.record(caller, AuditCategory.DATA_WRITE, AuditPlane.DATA, action, p.getWorkspaceId(),
                "change_proposal", p.getId().toString(), p.getId(), reason, details);
    }

    private static ResponseEntity<String> validation(HttpServletRequest request, String field, String message) {
        return ResponseEntity.status(ProblemCode.INVALID_ARGUMENT.httpStatus())
                .contentType(AdminApi.PROBLEM_JSON)
                .body(ProblemDetailFactory.buildValidation(request.getRequestURI(),
                        List.of(new FieldViolation(field, message))));
    }
}
