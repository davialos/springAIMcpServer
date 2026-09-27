package com.springaimcpservercommon.persistence.proposal;

import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.id.Ids;
import com.springaimcpservercommon.core.invocation.Channel;
import com.springaimcpservercommon.persistence.proposal.ProposalRuleViolationException.Reason;
import com.springaimcpservercommon.persistence.support.CanonicalJson;
import com.springaimcpservercommon.persistence.support.Checks;
import com.springaimcpservercommon.persistence.support.UtcTimes;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A reviewed write proposal — the aggregate root of {@code dai_change_proposal} with its records, state history and
 * approvals (ADR-0009, LLD-11). Nothing is ever written to host data by this class; it only records the decision
 * trail and enforces the lifecycle:
 *
 * <ul>
 *   <li>only the owner may edit, confirm or decline; confirmation must present the current content hash and happen
 *       before {@code expires_at} ({@code ck_change_proposal_confirmer});</li>
 *   <li>approvals and rejections come from principals other than the owner (segregation of duties, also enforced
 *       by the {@code trg_change_proposal_approval_sod} trigger), one decision per approver;</li>
 *   <li>every state change appends a {@link ChangeProposalEvent} with a contiguous {@code seq};</li>
 *   <li>illegal transitions throw {@link ProposalRuleViolationException}.</li>
 * </ul>
 * See {@link ProposalState} for the state diagram. Instances are changed only inside a unit transaction through
 * {@link ChangeProposalStore#transition}; the {@code row_version} column provides optimistic locking.
 */
@Entity
@Table(name = "dai_change_proposal")
public class ChangeProposal {

    /** Most records one proposal may touch. */
    public static final int MAX_RECORDS = 1000;

    /** Most approvals a proposal may require ({@code ck_change_proposal_required_approvals}). */
    public static final int MAX_REQUIRED_APPROVALS = 5;

    /** Longest summary accepted. */
    public static final int MAX_SUMMARY_LENGTH = 2000;

    /** Longest failure message accepted (sanitised, no user data). */
    public static final int MAX_FAILURE_MESSAGE_LENGTH = 2000;

    private static final Set<ProposalState> EDITABLE = EnumSet.of(ProposalState.PROPOSED, ProposalState.EDITED);
    private static final Set<ProposalState> CONFIRMED_OR_LATER = EnumSet.of(ProposalState.AWAITING_APPROVAL,
            ProposalState.CONFIRMED, ProposalState.APPLYING, ProposalState.APPLIED);
    private static final Set<ProposalState> BEFORE_APPLY_END = EnumSet.of(ProposalState.CONFIRMED,
            ProposalState.APPLYING);

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, updatable = false)
    private ProposalOrigin origin;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false)
    private Channel channel;

    @Column(name = "conversation_id", updatable = false)
    private @Nullable UUID conversationId;

    @Column(name = "turn_id", updatable = false)
    private @Nullable UUID turnId;

    @Column(name = "tool_invocation_id", updatable = false)
    private @Nullable UUID toolInvocationId;

    @Column(name = "mcp_session_id", updatable = false)
    private @Nullable UUID mcpSessionId;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_kind", nullable = false, updatable = false)
    private ProposalTargetKind targetKind;

    @Column(name = "target_ref", nullable = false, updatable = false)
    private String targetRef;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_args", updatable = false)
    private @Nullable String targetArgs;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_kind", nullable = false, updatable = false)
    private ChangeKind changeKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private ProposalState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_requirement", nullable = false)
    private ApprovalRequirement approvalRequirement;

    @Column(name = "required_approvals", nullable = false)
    private short requiredApprovals;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(name = "summary", nullable = false)
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation")
    private @Nullable String validation;

    @Column(name = "idempotency_key", updatable = false)
    private @Nullable String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "confirmed_at")
    private @Nullable Instant confirmedAt;

    @Column(name = "confirmed_by")
    private @Nullable UUID confirmedBy;

    @Column(name = "applied_at")
    private @Nullable Instant appliedAt;

    @Column(name = "host_revision_ref")
    private @Nullable String hostRevisionRef;

    @Column(name = "failure_code")
    private @Nullable String failureCode;

    @Column(name = "failure_message")
    private @Nullable String failureMessage;

    @Column(name = "retention_until", nullable = false)
    private Instant retentionUntil;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    /** Ordered by seq in {@link #getRecords()} (the seq lives in the embedded id). */
    @OneToMany(mappedBy = "proposal", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ChangeProposalRecord> records = new ArrayList<>();

    @OneToMany(mappedBy = "proposal", cascade = CascadeType.ALL)
    @OrderBy("seq")
    private List<ChangeProposalEvent> events = new ArrayList<>();

    @OneToMany(mappedBy = "proposal", cascade = CascadeType.ALL)
    @OrderBy("decidedAt")
    private List<ChangeProposalApproval> approvals = new ArrayList<>();

    /** For JPA only. */
    protected ChangeProposal() {
    }

    // ---------------------------------------------------------------------------------------------- creation

    /**
     * Creates a proposal in state PROPOSED with its records and the creating event (seq 0, actor = owner).
     *
     * @param data proposal data
     * @param now  current time
     * @return a new, unsaved aggregate with a fresh UUIDv7
     * @throws IllegalArgumentException if the data violates a {@code ck_change_proposal*} rule
     */
    public static ChangeProposal propose(NewChangeProposal data, Instant now) {
        ChangeProposal p = new ChangeProposal();
        p.id = Ids.newId();
        p.workspaceId = Checks.required(data.workspaceId(), "workspaceId");
        p.origin = Checks.required(data.origin(), "origin");
        p.channel = Checks.required(data.channel(), "channel");
        p.conversationId = data.conversationId();
        p.turnId = data.turnId();
        p.toolInvocationId = data.toolInvocationId();
        p.mcpSessionId = data.mcpSessionId();
        p.ownerId = Checks.required(data.ownerId(), "ownerId");
        p.targetKind = Checks.required(data.targetKind(), "targetKind");
        CatalogElementRef target = Checks.required(data.targetRef(), "targetRef");
        if (target.kind() != p.targetKind.refKind()) {
            throw new IllegalArgumentException(p.targetKind + " proposals need a " + p.targetKind.refKind()
                    + " target, not " + target);
        }
        p.targetRef = target.toString();
        p.targetArgs = Checks.optionalJson(data.targetArgsJson(), "targetArgsJson");
        p.changeKind = Checks.required(data.changeKind(), "changeKind");
        p.approvalRequirement = Checks.required(data.approvalRequirement(), "approvalRequirement");
        p.requiredApprovals = checkedApprovals(p.approvalRequirement, data.requiredApprovals());
        p.contentHash = Checks.sha256(data.contentHash(), "contentHash");
        p.summary = Checks.text(data.summary(), "summary", MAX_SUMMARY_LENGTH);
        p.validation = Checks.optionalJson(data.validationJson(), "validationJson");
        p.idempotencyKey = Checks.optionalText(data.idempotencyKey(), "idempotencyKey", 255);
        Duration ttl = positive(Checks.required(data.timeToLive(), "timeToLive"), "timeToLive");
        Duration retention = Checks.required(data.retention(), "retention");
        if (retention.isNegative()) {
            throw new IllegalArgumentException("retention must not be negative");
        }
        p.createdAt = UtcTimes.micros(now);
        p.expiresAt = UtcTimes.micros(p.createdAt.plus(ttl));
        p.retentionUntil = UtcTimes.micros(p.expiresAt.plus(retention));
        p.state = ProposalState.PROPOSED;

        List<NewProposalRecord> records = Checks.required(data.records(), "records");
        if (records.isEmpty() || records.size() > MAX_RECORDS) {
            throw new IllegalArgumentException("a proposal needs 1 to " + MAX_RECORDS + " records");
        }
        if (p.changeKind != ChangeKind.BULK && records.size() != 1) {
            throw new IllegalArgumentException(p.changeKind + " proposals touch exactly one record");
        }
        for (int i = 0; i < records.size(); i++) {
            p.records.add(ChangeProposalRecord.of(p, i, records.get(i)));
        }
        p.appendEvent(null, ProposalState.PROPOSED, p.ownerId, p.createdAt,
                details("records", records.size(), "approvalRequirement", p.approvalRequirement,
                        "contentHash", p.contentHash));
        return p;
    }

    // ---------------------------------------------------------------------------------------------- owner actions

    /**
     * The owner edits proposed values; the proposing layer supplies the recomputed content hash (and validation).
     *
     * @param actorId        acting principal (must be the owner)
     * @param edits          new after-values per record
     * @param newContentHash hash over target and edited changes
     * @param validationJson new validation report, if any
     * @param now            current time
     */
    public void edit(UUID actorId, List<RecordEdit> edits, String newContentHash, @Nullable String validationJson,
                     Instant now) {
        requireOwner(actorId, "edit");
        requireState(EDITABLE, "edit");
        requireNotExpired(now);
        if (edits.isEmpty()) {
            throw new IllegalArgumentException("an edit needs at least one record change");
        }
        String hash = Checks.sha256(newContentHash, "newContentHash");
        List<Integer> editedSeqs = new ArrayList<>();
        for (RecordEdit edit : edits) {
            recordAt(edit.seq()).replaceAfterValues(edit.afterValuesJson());
            editedSeqs.add(edit.seq());
        }
        contentHash = hash;
        validation = Checks.optionalJson(validationJson, "validationJson");
        moveTo(ProposalState.EDITED, actorId, now, details("editedRecords", editedSeqs));
    }

    /**
     * Raises the approval requirement (policy decided after creation, e.g. a BULK proposal over a threshold).
     * The state does not change; an event with {@code from_state = to_state} records the change. Requirements are
     * never lowered.
     *
     * @param requiredApprovalCount approvals required (1–{@value #MAX_REQUIRED_APPROVALS})
     * @param actorId               principal or {@code null} for the policy engine
     * @param now                   current time
     */
    public void requireApproval(int requiredApprovalCount, @Nullable UUID actorId, Instant now) {
        requireState(EDITABLE, "require approval for");
        short count = checkedApprovals(ApprovalRequirement.SELF_CONFIRM_PLUS_APPROVER, requiredApprovalCount);
        if (approvalRequirement == ApprovalRequirement.SELF_CONFIRM_PLUS_APPROVER && requiredApprovals >= count) {
            return;
        }
        approvalRequirement = ApprovalRequirement.SELF_CONFIRM_PLUS_APPROVER;
        requiredApprovals = count;
        appendEvent(state, state, actorId, now, details("requiredApprovals", (int) count));
    }

    /**
     * The owner confirms the proposal. Requires the current content hash and an unexpired proposal. Moves to
     * CONFIRMED, or to AWAITING_APPROVAL when approvals are required. Repeating an identical confirmation
     * (double-click, retried request) is a no-op.
     *
     * @param actorId     acting principal (must be the owner)
     * @param contentHash hash the user saw
     * @param now         current time
     * @return {@code true} if the state changed, {@code false} for a repeated identical confirmation
     */
    public boolean confirm(UUID actorId, String contentHash, Instant now) {
        requireOwner(actorId, "confirm");
        if (CONFIRMED_OR_LATER.contains(state) && actorId.equals(confirmedBy) && this.contentHash.equals(contentHash)) {
            return false;
        }
        requireState(EDITABLE, "confirm");
        requireNotExpired(now);
        if (!this.contentHash.equals(contentHash)) {
            throw new ProposalRuleViolationException(Reason.CONTENT_HASH_MISMATCH,
                    "proposal " + id + " changed since it was shown; review it again");
        }
        confirmedAt = UtcTimes.micros(now);
        confirmedBy = actorId;
        ProposalState next = approvalRequirement == ApprovalRequirement.SELF_CONFIRM
                ? ProposalState.CONFIRMED : ProposalState.AWAITING_APPROVAL;
        moveTo(next, actorId, now, null);
        return true;
    }

    /**
     * The owner withdraws a pending proposal (PROPOSED, EDITED or AWAITING_APPROVAL) → REJECTED.
     *
     * @param actorId acting principal (must be the owner)
     * @param reason  optional reason code (no user content)
     * @param now     current time
     */
    public void decline(UUID actorId, @Nullable String reason, Instant now) {
        requireOwner(actorId, "decline");
        requireState(EnumSet.of(ProposalState.PROPOSED, ProposalState.EDITED, ProposalState.AWAITING_APPROVAL),
                "decline");
        moveTo(ProposalState.REJECTED, actorId, now,
                details("declinedByOwner", true, "reason", Checks.optionalText(reason, "reason", 200)));
    }

    // ---------------------------------------------------------------------------------------------- approvers

    /**
     * A principal other than the owner approves; when the required number of approvals is reached the proposal is
     * CONFIRMED.
     *
     * @param approverId approving principal (must not be the owner)
     * @param comment    optional comment
     * @param now        current time
     * @return {@code true} if this approval completed the requirement
     */
    public boolean approve(UUID approverId, @Nullable String comment, Instant now) {
        requireApprover(approverId);
        requireState(EnumSet.of(ProposalState.AWAITING_APPROVAL), "approve");
        requireNotExpired(now);
        requireUndecided(approverId);
        approvals.add(ChangeProposalApproval.of(this, approverId, ApprovalDecision.APPROVED, comment, now));
        long approved = approvals.stream().filter(a -> a.getDecision() == ApprovalDecision.APPROVED).count();
        if (approved >= requiredApprovals) {
            moveTo(ProposalState.CONFIRMED, approverId, now, details("approvals", approved));
            return true;
        }
        return false;
    }

    /**
     * A principal other than the owner rejects an awaiting proposal → REJECTED.
     *
     * @param approverId rejecting principal (must not be the owner)
     * @param comment    mandatory comment ({@code ck_change_proposal_approval_comment})
     * @param now        current time
     */
    public void reject(UUID approverId, String comment, Instant now) {
        requireApprover(approverId);
        requireState(EnumSet.of(ProposalState.AWAITING_APPROVAL), "reject");
        requireUndecided(approverId);
        approvals.add(ChangeProposalApproval.of(this, approverId, ApprovalDecision.REJECTED, comment, now));
        moveTo(ProposalState.REJECTED, approverId, now, details("rejectedByApprover", true));
    }

    // ---------------------------------------------------------------------------------------------- apply

    /**
     * Marks the start of applying a confirmed proposal (CONFIRMED → APPLYING). The confirming owner is recorded as
     * actor because the apply runs as that user (ADR-0008).
     *
     * @param now current time
     */
    public void markApplying(Instant now) {
        requireState(EnumSet.of(ProposalState.CONFIRMED), "start applying");
        moveTo(ProposalState.APPLYING, confirmedBy, now, null);
    }

    /**
     * Records a successful apply (APPLYING → APPLIED).
     *
     * @param hostRevisionRef the host revision created by the write (Envers revision, version, …), if any
     * @param now             current time
     */
    public void markApplied(@Nullable String hostRevisionRef, Instant now) {
        requireState(EnumSet.of(ProposalState.APPLYING), "mark applied");
        this.hostRevisionRef = Checks.optionalText(hostRevisionRef, "hostRevisionRef", 512);
        appliedAt = UtcTimes.micros(now);
        moveTo(ProposalState.APPLIED, confirmedBy, now, details("hostRevisionRef", this.hostRevisionRef));
    }

    /**
     * Records that the target changed since the proposal was made (CONFIRMED or APPLYING → CONFLICT).
     *
     * @param code    failure code
     * @param message sanitised message (no user data), if any
     * @param now     current time
     */
    public void markConflict(String code, @Nullable String message, Instant now) {
        fail(ProposalState.CONFLICT, code, message, now);
    }

    /**
     * Records a failed apply; nothing was committed (CONFIRMED or APPLYING → FAILED).
     *
     * @param code    failure code
     * @param message sanitised message (no user data), if any
     * @param now     current time
     */
    public void markFailed(String code, @Nullable String message, Instant now) {
        fail(ProposalState.FAILED, code, message, now);
    }

    /**
     * Expires a pending proposal whose {@code expires_at} has passed (system action, no actor).
     *
     * @param now current time
     */
    public void expire(Instant now) {
        requireState(EnumSet.of(ProposalState.PROPOSED, ProposalState.EDITED, ProposalState.AWAITING_APPROVAL),
                "expire");
        if (now.isBefore(expiresAt)) {
            throw new ProposalRuleViolationException(Reason.ILLEGAL_TRANSITION,
                    "proposal " + id + " expires at " + expiresAt + ", not yet");
        }
        moveTo(ProposalState.EXPIRED, null, now, null);
    }

    /**
     * Extends {@code retention_until} (never shortens it), e.g. to keep a terminal proposal for the configured
     * retention after it ended.
     *
     * @param until new retention end
     */
    public void retainUntil(Instant until) {
        Instant candidate = UtcTimes.micros(until);
        if (candidate.isAfter(retentionUntil)) {
            retentionUntil = candidate;
        }
    }

    // ---------------------------------------------------------------------------------------------- internals

    private void fail(ProposalState target, String code, @Nullable String message, Instant now) {
        requireState(BEFORE_APPLY_END, "mark " + target.name().toLowerCase(java.util.Locale.ROOT));
        failureCode = Checks.text(code, "code", 128);
        failureMessage = Checks.optionalText(message, "message", MAX_FAILURE_MESSAGE_LENGTH);
        moveTo(target, confirmedBy, now, details("failureCode", failureCode));
    }

    private void moveTo(ProposalState target, @Nullable UUID actorId, Instant now, @Nullable String detailsJson) {
        ProposalState from = state;
        state = target;
        appendEvent(from, target, actorId, now, detailsJson);
    }

    private void appendEvent(@Nullable ProposalState from, ProposalState to, @Nullable UUID actorId, Instant now,
                             @Nullable String detailsJson) {
        events.add(ChangeProposalEvent.of(this, events.size(), from, to, actorId, now, detailsJson));
    }

    private void requireOwner(UUID actorId, String action) {
        if (!ownerId.equals(Checks.required(actorId, "actorId"))) {
            throw new ProposalRuleViolationException(Reason.NOT_OWNER,
                    "only the owner may " + action + " proposal " + id);
        }
    }

    private void requireApprover(UUID approverId) {
        if (ownerId.equals(Checks.required(approverId, "approverId"))) {
            throw new ProposalRuleViolationException(Reason.OWNER_CANNOT_APPROVE,
                    "the owner cannot approve or reject their own proposal " + id);
        }
    }

    private void requireUndecided(UUID approverId) {
        for (ChangeProposalApproval approval : approvals) {
            if (approval.getApproverId().equals(approverId)) {
                throw new ProposalRuleViolationException(Reason.ALREADY_DECIDED,
                        "approver already decided on proposal " + id);
            }
        }
    }

    private void requireState(Set<ProposalState> allowed, String action) {
        if (!allowed.contains(state)) {
            throw new ProposalRuleViolationException(Reason.ILLEGAL_TRANSITION,
                    "cannot " + action + " proposal " + id + " in state " + state);
        }
    }

    private void requireNotExpired(Instant now) {
        if (!now.isBefore(expiresAt)) {
            throw new ProposalRuleViolationException(Reason.EXPIRED, "proposal " + id + " expired at " + expiresAt);
        }
    }

    private ChangeProposalRecord recordAt(int seq) {
        for (ChangeProposalRecord record : records) {
            if (record.getSeq() == seq) {
                return record;
            }
        }
        throw new IllegalArgumentException("proposal " + id + " has no record " + seq);
    }

    private static short checkedApprovals(ApprovalRequirement requirement, int count) {
        boolean valid = requirement == ApprovalRequirement.SELF_CONFIRM
                ? count == 0
                : count >= 1 && count <= MAX_REQUIRED_APPROVALS;
        if (!valid) {
            throw new IllegalArgumentException(requirement + " needs "
                    + (requirement == ApprovalRequirement.SELF_CONFIRM ? "0" : "1-" + MAX_REQUIRED_APPROVALS)
                    + " required approvals, not " + count);
        }
        return (short) count;
    }

    private static Duration positive(Duration duration, String name) {
        if (duration.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException(name + " must be at least one second");
        }
        return duration;
    }

    private static @Nullable String details(@Nullable Object... keyValues) {
        Map<String, @Nullable Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            if (value != null) {
                map.put(String.valueOf(keyValues[i]), value);
            }
        }
        return map.isEmpty() ? null : CanonicalJson.write(map);
    }

    /**
     * Initialises the lazy collections so the aggregate can be read after its transaction ended.
     */
    void initializeAssociations() {
        records.size();
        events.size();
        approvals.size();
    }

    // ---------------------------------------------------------------------------------------------- accessors

    /** @return proposal id */
    public UUID getId() {
        return id;
    }

    /** @return workspace id */
    public UUID getWorkspaceId() {
        return workspaceId;
    }

    /** @return origin */
    public ProposalOrigin getOrigin() {
        return origin;
    }

    /** @return entry channel */
    public Channel getChannel() {
        return channel;
    }

    /** @return conversation id, if any */
    public @Nullable UUID getConversationId() {
        return conversationId;
    }

    /** @return agent turn id, if any */
    public @Nullable UUID getTurnId() {
        return turnId;
    }

    /** @return proposing tool invocation id, if any */
    public @Nullable UUID getToolInvocationId() {
        return toolInvocationId;
    }

    /** @return MCP session id, if any */
    public @Nullable UUID getMcpSessionId() {
        return mcpSessionId;
    }

    /** @return owner principal id */
    public UUID getOwnerId() {
        return ownerId;
    }

    /** @return target kind */
    public ProposalTargetKind getTargetKind() {
        return targetKind;
    }

    /** @return target reference */
    public CatalogElementRef getTargetRef() {
        return CatalogElementRef.parse(targetRef);
    }

    /** @return operation arguments JSON, if any */
    public @Nullable String getTargetArgsJson() {
        return targetArgs;
    }

    /** @return change kind */
    public ChangeKind getChangeKind() {
        return changeKind;
    }

    /** @return current state */
    public ProposalState getState() {
        return state;
    }

    /** @return approval requirement */
    public ApprovalRequirement getApprovalRequirement() {
        return approvalRequirement;
    }

    /** @return number of approvals required */
    public int getRequiredApprovals() {
        return requiredApprovals;
    }

    /** @return current content hash */
    public String getContentHash() {
        return contentHash;
    }

    /** @return summary */
    public String getSummary() {
        return summary;
    }

    /** @return validation report JSON, if any */
    public @Nullable String getValidationJson() {
        return validation;
    }

    /** @return idempotency key, if any */
    public @Nullable String getIdempotencyKey() {
        return idempotencyKey;
    }

    /** @return creation time */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** @return expiry time */
    public Instant getExpiresAt() {
        return expiresAt;
    }

    /** @return owner confirmation time, if confirmed */
    public @Nullable Instant getConfirmedAt() {
        return confirmedAt;
    }

    /** @return confirming principal (always the owner), if confirmed */
    public @Nullable UUID getConfirmedBy() {
        return confirmedBy;
    }

    /** @return apply time, if applied */
    public @Nullable Instant getAppliedAt() {
        return appliedAt;
    }

    /** @return host revision reference, if applied and known */
    public @Nullable String getHostRevisionRef() {
        return hostRevisionRef;
    }

    /** @return failure code, if CONFLICT or FAILED */
    public @Nullable String getFailureCode() {
        return failureCode;
    }

    /** @return sanitised failure message, if any */
    public @Nullable String getFailureMessage() {
        return failureMessage;
    }

    /** @return retention end */
    public Instant getRetentionUntil() {
        return retentionUntil;
    }

    /** @return optimistic-lock version (use as ETag / If-Match value) */
    public long getRowVersion() {
        return rowVersion;
    }

    /** @return records in order (unmodifiable) */
    public List<ChangeProposalRecord> getRecords() {
        return records.stream().sorted(java.util.Comparator.comparingInt(ChangeProposalRecord::getSeq)).toList();
    }

    /** @return history in order (unmodifiable) */
    public List<ChangeProposalEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    /** @return second-person decisions (unmodifiable) */
    public List<ChangeProposalApproval> getApprovals() {
        return Collections.unmodifiableList(approvals);
    }

    /**
     * The content hash the proposal was created with (recorded in the creating event), used to recognise retried
     * creation requests after the owner edited the proposal.
     *
     * @return the initial content hash, or {@code null} if the creating event is not available
     */
    public @Nullable String getInitialContentHash() {
        for (ChangeProposalEvent event : events) {
            String json = event.getDetailsJson();
            if (event.getSeq() == 0 && json != null
                    && CanonicalJson.parse(json) instanceof Map<?, ?> map
                    && map.get("contentHash") instanceof String hash) {
                return hash;
            }
        }
        return null;
    }

    /**
     * Principals who already decided (approved or rejected).
     *
     * @return approver ids
     */
    public Set<UUID> getDeciders() {
        Set<UUID> ids = new HashSet<>();
        approvals.forEach(a -> ids.add(a.getApproverId()));
        return Set.copyOf(ids);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof ChangeProposal other && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "ChangeProposal[" + id + ", " + state + "]";
    }
}
