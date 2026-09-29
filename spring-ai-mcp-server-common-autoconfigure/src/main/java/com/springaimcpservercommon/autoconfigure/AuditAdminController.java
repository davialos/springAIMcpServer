package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.persistence.audit.AuditChain;
import com.springaimcpservercommon.persistence.audit.AuditEvent;
import com.springaimcpservercommon.persistence.audit.AuditTrail;
import com.springaimcpservercommon.persistence.audit.ChainVerification;
import com.springaimcpservercommon.persistence.support.PageRequest;
import com.springaimcpservercommon.persistence.support.Slice;
import com.springaimcpservercommon.persistence.support.TimeRange;
import com.springaimcpservercommon.security.permission.Permission;
import com.springaimcpservercommon.webmvc.problem.ProblemCode;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Audit log viewer API (LLD-08 §2, F-66): read-only, time-windowed, paged views over the tamper-evident
 * audit trail, plus hash-chain verification. All endpoints require {@link Permission#AUDIT_READ}.
 *
 * <p>Not a {@code @Component}; registered by {@link DaiAdminAutoConfiguration}.
 */
@NullMarked
@RequestMapping("/dynamic-ai/admin/api/v1/audit")
public final class AuditAdminController {

    /** Upper bound of a single verification pass, to bound the cost of one request. */
    static final long MAX_VERIFY_SPAN = 100_000L;

    /**
     * Page of audit events.
     *
     * @param items   events, newest first as returned by the trail
     * @param limit   page size used
     * @param offset  page offset used
     * @param hasMore whether another page exists
     * @param from    inclusive window start
     * @param to      exclusive window end
     */
    public record EventPage(List<EventView> items, int limit, int offset, boolean hasMore, Instant from, Instant to) {}

    /**
     * One audit event as shown to the UI. {@code detailsJson} is the stored, already-redacted details object.
     *
     * @param id          event id
     * @param occurredAt  event time (UTC)
     * @param chainId     hash-chain id
     * @param chainSeq    position in the chain
     * @param category    event category
     * @param action      action code
     * @param plane       control/data/agent/system plane
     * @param actorId     acting principal, if any
     * @param actorType   actor type
     * @param workspaceId workspace, if scoped
     * @param resourceType affected resource type
     * @param resourceId  affected resource id
     * @param decision    PERMIT, DENY or NOT_APPLICABLE
     * @param reason      denial or change reason
     * @param traceId     trace correlation id
     * @param turnId      agent turn correlation id
     * @param proposalId  change proposal correlation id
     * @param detailsJson stored details object
     * @param hash        this event's hash
     */
    public record EventView(
            UUID id, Instant occurredAt, String chainId, long chainSeq, String category, String action,
            String plane, @Nullable UUID actorId, String actorType, @Nullable UUID workspaceId,
            @Nullable String resourceType, @Nullable String resourceId, String decision, @Nullable String reason,
            @Nullable String traceId, @Nullable UUID turnId, @Nullable UUID proposalId,
            @Nullable String detailsJson, String hash) {

        static EventView of(AuditEvent e) {
            return new EventView(e.getId(), e.getOccurredAt(), e.getChainId(), e.getChainSeq(),
                    e.getCategory().name(), e.getAction(), e.getPlane().name(), e.getActorId(),
                    e.getActorType().name(), e.getWorkspaceId(), e.getResourceType(), e.getResourceId(),
                    e.getDecision().name(), e.getReason(), e.getTraceId(), e.getTurnId(), e.getProposalId(),
                    e.getDetailsJson(), e.getHash());
        }
    }

    /**
     * Result of a hash-chain verification pass.
     *
     * @param chainId       verified chain
     * @param fromSeq       first sequence checked
     * @param toSeq         last sequence checked
     * @param eventsChecked number of events read
     * @param intact        {@code true} when no break was found
     * @param firstBrokenSeq first broken sequence, if any
     * @param problem       description of the break, if any
     * @param anchored      whether the range starts at a trusted anchor
     */
    public record VerificationView(String chainId, long fromSeq, long toSeq, long eventsChecked, boolean intact,
                                   @Nullable Long firstBrokenSeq, @Nullable String problem, boolean anchored) {}

    private final AuditTrail auditTrail;
    private final AdminApi api;
    private final Clock clock;

    AuditAdminController(AuditTrail auditTrail, AdminApi api, Clock clock) {
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail");
        this.api = Objects.requireNonNull(api, "api");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Events of one workspace within a time window.
     *
     * @param workspaceId workspace
     * @param from        inclusive ISO-8601 start; default 24 h before {@code to}
     * @param to          exclusive ISO-8601 end; default now
     * @param limit       page size (1..200, default 50)
     * @param offset      page offset
     * @param request     current request
     * @return 200 with a page, 401/403/400 problems
     */
    @GetMapping("/workspaces/{workspaceId}/events")
    public ResponseEntity<?> workspaceEvents(
            @PathVariable UUID workspaceId,
            @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable Integer offset,
            HttpServletRequest request) {
        var gate = api.gate(request, Permission.AUDIT_READ, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        TimeRange range = AdminApi.window(from, to, clock.instant());
        PageRequest page = AdminApi.page(limit, offset);
        return ResponseEntity.ok(toPage(auditTrail.eventsOfWorkspace(workspaceId, range, page), range));
    }

    /**
     * Events performed by one actor within a time window.
     *
     * @param actorId acting principal
     * @param from    inclusive ISO-8601 start
     * @param to      exclusive ISO-8601 end
     * @param limit   page size
     * @param offset  page offset
     * @param request current request
     * @return 200 with a page
     */
    @GetMapping("/actors/{actorId}/events")
    public ResponseEntity<?> actorEvents(
            @PathVariable UUID actorId,
            @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable Integer offset,
            HttpServletRequest request) {
        var gate = api.gate(request, Permission.AUDIT_READ, null);
        if (!gate.open()) {
            return gate.denied();
        }
        TimeRange range = AdminApi.window(from, to, clock.instant());
        PageRequest page = AdminApi.page(limit, offset);
        return ResponseEntity.ok(toPage(auditTrail.eventsOfActor(actorId, range, page), range));
    }

    /**
     * Authorization and policy denials across the installation within a time window.
     *
     * @param from    inclusive ISO-8601 start
     * @param to      exclusive ISO-8601 end
     * @param limit   page size
     * @param offset  page offset
     * @param request current request
     * @return 200 with a page
     */
    @GetMapping("/denials")
    public ResponseEntity<?> denials(
            @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable Integer offset,
            HttpServletRequest request) {
        var gate = api.gate(request, Permission.AUDIT_READ, null);
        if (!gate.open()) {
            return gate.denied();
        }
        TimeRange range = AdminApi.window(from, to, clock.instant());
        PageRequest page = AdminApi.page(limit, offset);
        return ResponseEntity.ok(toPage(auditTrail.denials(range, page), range));
    }

    /**
     * Decision trail of one change proposal.
     *
     * @param proposalId proposal
     * @param request    current request
     * @return 200 with events in chain order
     */
    @GetMapping("/proposals/{proposalId}/events")
    public ResponseEntity<?> proposalEvents(@PathVariable UUID proposalId, HttpServletRequest request) {
        var gate = api.gate(request, Permission.AUDIT_READ, null);
        if (!gate.open()) {
            return gate.denied();
        }
        return ResponseEntity.ok(auditTrail.eventsOfProposal(proposalId).stream().map(EventView::of).toList());
    }

    /**
     * Audit trail of one agent turn (trace-viewer companion, F-72).
     *
     * @param turnId  agent turn
     * @param request current request
     * @return 200 with events in chain order
     */
    @GetMapping("/turns/{turnId}/events")
    public ResponseEntity<?> turnEvents(@PathVariable UUID turnId, HttpServletRequest request) {
        var gate = api.gate(request, Permission.AUDIT_READ, null);
        if (!gate.open()) {
            return gate.denied();
        }
        return ResponseEntity.ok(auditTrail.eventsOfTurn(turnId).stream().map(EventView::of).toList());
    }

    /**
     * Verifies a stretch of a hash chain. Defaults to the whole chain when it is at most
     * {@value #MAX_VERIFY_SPAN} events long, else to the newest {@value #MAX_VERIFY_SPAN}.
     *
     * @param chainId chain id (the system chain or a workspace id)
     * @param fromSeq first sequence to check
     * @param toSeq   last sequence to check
     * @param request current request
     * @return 200 with the verification, 404 for an unknown chain, 400 for a bad range
     */
    @GetMapping("/chains/{chainId}/verify")
    public ResponseEntity<?> verifyChain(
            @PathVariable String chainId,
            @RequestParam(required = false) @Nullable Long fromSeq,
            @RequestParam(required = false) @Nullable Long toSeq,
            HttpServletRequest request) {
        var gate = api.gate(request, Permission.AUDIT_READ, null);
        if (!gate.open()) {
            return gate.denied();
        }
        if (!AuditTrail.CHAIN_ID.matcher(chainId).matches()) {
            throw new IllegalArgumentException("chainId must match " + AuditTrail.CHAIN_ID.pattern());
        }
        Optional<AuditChain> head = auditTrail.head(chainId);
        if (head.isEmpty()) {
            return AdminApi.problem(ProblemCode.NOT_FOUND, "Chain not found", null, request);
        }
        long last = head.get().getLastSeq();
        long to = toSeq == null ? last : Math.min(toSeq, last);
        long from = fromSeq == null ? Math.max(1, to - MAX_VERIFY_SPAN + 1) : fromSeq;
        if (from < 1 || from > to) {
            throw new IllegalArgumentException("fromSeq must be between 1 and toSeq");
        }
        if (to - from + 1 > MAX_VERIFY_SPAN) {
            throw new IllegalArgumentException("at most " + MAX_VERIFY_SPAN + " events can be verified per request");
        }
        ChainVerification v = auditTrail.verify(chainId, from, to);
        return ResponseEntity.ok(new VerificationView(v.chainId(), v.fromSeq(), v.toSeq(), v.eventsChecked(),
                v.intact(), v.firstBrokenSeq(), v.problem(), v.anchored()));
    }

    private static EventPage toPage(Slice<AuditEvent> slice, TimeRange range) {
        return new EventPage(slice.items().stream().map(EventView::of).toList(), slice.page().limit(),
                slice.page().offset(), slice.hasMore(), range.from(), range.to());
    }
}
