package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.catalog.EffectiveOperation;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.persistence.proposal.ChangeProposal;
import com.springaimcpservercommon.persistence.proposal.ChangeProposalStore;
import com.springaimcpservercommon.persistence.proposal.ProposalRuleViolationException;
import com.springaimcpservercommon.persistence.proposal.ProposalState;
import com.springaimcpservercommon.persistence.proposal.ProposalTargetKind;
import com.springaimcpservercommon.security.permission.Permission;
import com.springaimcpservercommon.webmvc.problem.ProblemCode;
import com.springaimcpservercommon.webmvc.endpoint.DispatchingBackingExecutor;
import com.springaimcpservercommon.webmvc.endpoint.GenericDynamicHandler;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * The write executor (F-45, LLD-11 §2, §6, §10, ADR-0009): applies a <em>confirmed</em> change proposal by running the
 * host operation it names, through the host's own Spring proxy, on the request thread of the owner who confirmed it.
 *
 * <p>Why the host's own path and the owner's own thread: the host's method security, transactions, validation,
 * {@code @Version} checks, Spring Data auditing, Envers and domain events all run exactly as if the user had used the
 * host UI, and they see the real user in the {@code SecurityContext} (ADR-0008). This class never writes with SQL, never
 * runs as a service account, and is reachable only from the review API's HTTP endpoints, never from a tool call, so a
 * model cannot cause a write (ADR-0009).
 *
 * <p>Before anything runs it re-checks, in this order: the proposal is the caller's and is {@code CONFIRMED} (all
 * approvals in); writes are still enabled; the caller still holds {@code data:write-confirm} in the workspace; the
 * proposal has not expired; the operation still exists, is enabled and is not read-only; the stored content still hashes
 * to what was confirmed. Then {@code CONFIRMED → APPLYING} is a compare-and-set, so a double click or a second node
 * cannot run it twice. The outcome is {@code APPLIED}, {@code CONFLICT} (the host reported an optimistic-lock failure)
 * or {@code FAILED}; the host's exception message is never stored or returned (it can carry data), only a fixed text.
 *
 * <p>If the process dies after the host committed but before {@code APPLIED} is written, the proposal stays
 * {@code APPLYING} and the maintenance runner later marks it {@code FAILED/APPLY_TIMEOUT} for an operator to verify:
 * the outcome is unknown, never retried automatically. No host revision reference is recorded yet (no
 * {@code VersioningAdapter}, OQ-36). At most {@code write.max-concurrent-applies} run at once per node.
 */
@NullMarked
final class ProposalApplier {

    private static final Logger LOG = LoggerFactory.getLogger(ProposalApplier.class);

    /** A precondition failed before anything ran; the proposal is unchanged. Messages are fixed text. */
    static final class ApplyRefusedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String code;

        ApplyRefusedException(String code, String message) {
            super(message, null, false, false);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    /**
     * Outcome of {@link #apply}.
     *
     * @param proposal  the proposal afterwards
     * @param attempted whether this call ran (or tried to run) the host operation; {@code false} when the proposal
     *                  was already applied, so a retried request is not audited twice
     */
    record Result(ChangeProposal proposal, boolean attempted) {}

    /** The proposal operations the applier needs (the store, or a fake in tests). */
    interface Proposals {
        Optional<ChangeProposal> findForOwner(UUID id, UUID ownerId);

        ChangeProposal markApplying(UUID id);

        ChangeProposal markApplied(UUID id, @Nullable String hostRevisionRef);

        ChangeProposal markConflict(UUID id, String code, @Nullable String message);

        ChangeProposal markFailed(UUID id, String code, @Nullable String message);

        /** Adapts the store. */
        static Proposals over(ChangeProposalStore store) {
            return new Proposals() {
                @Override
                public Optional<ChangeProposal> findForOwner(UUID id, UUID ownerId) {
                    return store.findForOwner(id, ownerId);
                }

                @Override
                public ChangeProposal markApplying(UUID id) {
                    return store.markApplying(id);
                }

                @Override
                public ChangeProposal markApplied(UUID id, @Nullable String hostRevisionRef) {
                    return store.markApplied(id, hostRevisionRef);
                }

                @Override
                public ChangeProposal markConflict(UUID id, String code, @Nullable String message) {
                    return store.markConflict(id, code, message);
                }

                @Override
                public ChangeProposal markFailed(UUID id, String code, @Nullable String message) {
                    return store.markFailed(id, code, message);
                }
            };
        }
    }

    /** Checks a permission of the caller in a workspace. */
    @FunctionalInterface
    interface PermissionCheck {
        boolean permits(DaiPrincipal caller, Permission permission, UUID workspaceId);
    }

    private final Proposals store;
    private final Supplier<EffectiveCatalog> catalog;
    private final Supplier<DispatchingBackingExecutor.OperationBackingHandler> handler;
    private final PermissionCheck permissions;
    private final DaiProperties.Write settings;
    private final Clock clock;
    private final Semaphore bulkhead;

    ProposalApplier(Proposals store, Supplier<EffectiveCatalog> catalog,
                    Supplier<DispatchingBackingExecutor.OperationBackingHandler> handler, PermissionCheck permissions,
                    DaiProperties.Write settings, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.bulkhead = new Semaphore(settings.maxConcurrentApplies());
    }

    /** Whether applying is switched on at all ({@code dynamic.ai.agent.write.enabled}). */
    boolean enabled() {
        return settings.enabled();
    }

    /**
     * Applies a confirmed proposal as its owner.
     *
     * @param proposalId the proposal
     * @param caller     the authenticated owner; the host operation runs in their {@code SecurityContext}
     * @return the proposal after the attempt: {@code APPLIED}, {@code FAILED} or {@code CONFLICT} (an already
     *         {@code APPLIED} proposal is returned as is, so a retried request is harmless)
     * @throws NoSuchElementException          when there is no such proposal for this owner
     * @throws ProposalRuleViolationException  when the proposal is not {@code CONFIRMED} (409) or has expired (410)
     * @throws ApplyRefusedException           when writes are off, the caller lacks the permission, or the node is busy
     */
    Result apply(UUID proposalId, DaiPrincipal caller) {
        ChangeProposal proposal = store.findForOwner(proposalId, caller.principalId())
                .orElseThrow(() -> new NoSuchElementException("proposal"));
        if (proposal.getState() == ProposalState.APPLIED) {
            return new Result(proposal, false);
        }
        if (proposal.getState() != ProposalState.CONFIRMED) {
            throw new ProposalRuleViolationException(ProposalRuleViolationException.Reason.ILLEGAL_TRANSITION,
                    "only a confirmed proposal can be applied, this one is " + proposal.getState());
        }
        if (!settings.enabled()) {
            throw new ApplyRefusedException("writes_disabled", "Applying changes is switched off on this system.");
        }
        if (!permissions.permits(caller, Permission.DATA_WRITE_CONFIRM, proposal.getWorkspaceId())) {
            throw new ApplyRefusedException("access_denied", "You may no longer confirm changes in this workspace.");
        }
        if (!clock.instant().isBefore(proposal.getExpiresAt())) {
            store.markFailed(proposalId, "expired", "The proposal expired before it was applied.");
            SafeMetrics.count("dynamic.ai.agent.proposals", "state", "FAILED", "kind", kind(proposal), "origin", "-");
            throw new ProposalRuleViolationException(ProposalRuleViolationException.Reason.EXPIRED,
                    "the proposal expired before it was applied");
        }
        if (!bulkhead.tryAcquire()) {
            throw new ApplyRefusedException("apply_busy", "Too many changes are being applied right now. Retry shortly.");
        }
        try {
            return new Result(applyConfirmed(proposal, caller), true);
        } finally {
            bulkhead.release();
        }
    }

    private ChangeProposal applyConfirmed(ChangeProposal proposal, DaiPrincipal caller) {
        UUID id = proposal.getId();
        String invalid = validate(proposal);
        if (invalid != null) {
            return failed(id, invalid, "The proposal can no longer be applied as reviewed.");
        }
        Map<String, Object> arguments = StoreProposalService.arguments(
                proposal.getTargetArgsJson() == null ? "{}" : proposal.getTargetArgsJson());
        // compare-and-set CONFIRMED -> APPLYING: exactly one caller proceeds
        store.markApplying(id);
        try {
            handler.get().execute(proposal.getTargetRef(), arguments, caller);
        } catch (GenericDynamicHandler.BackingException e) {
            if (isOptimisticLock(e)) {
                ChangeProposal conflict = store.markConflict(id, "version_conflict",
                        "The record was changed by someone else since the proposal was made.");
                return finished(conflict, "CONFLICT");
            }
            String code = e.code() == ProblemCode.ACCESS_DENIED
                    ? "access_denied" : "execution_error";
            return finished(store.markFailed(id, code, code.equals("access_denied")
                    ? "The host refused the change for this user." : "The host could not apply the change."),
                    "FAILED");
        } catch (RuntimeException e) {
            LOG.warn("Applying proposal {} failed ({})", id, e.getClass().getSimpleName());
            return finished(store.markFailed(id, "execution_error", "The host could not apply the change."),
                    "FAILED");
        }
        ChangeProposal applied = store.markApplied(id, null);
        LOG.info("Proposal {} applied for principal {}", id, caller.principalId());
        return finished(applied, "APPLIED");
    }

    /** @return {@code null} when the stored proposal is still valid to apply, else the failure code */
    private @Nullable String validate(ChangeProposal proposal) {
        if (proposal.getTargetKind() != ProposalTargetKind.HOST_OPERATION) {
            return "unsupported_target";
        }
        EffectiveOperation operation = catalog.get().operation(proposal.getTargetRef()).orElse(null);
        if (operation == null || !operation.enabled() || operation.readOnly()) {
            return "operation_unavailable";
        }
        Map<String, Object> arguments;
        try {
            arguments = StoreProposalService.arguments(
                    proposal.getTargetArgsJson() == null ? "{}" : proposal.getTargetArgsJson());
        } catch (RuntimeException e) {
            return "content_mismatch";
        }
        String hash = StoreProposalService.contentHash(proposal.getTargetRef(), proposal.getChangeKind(), arguments);
        if (!hash.equals(proposal.getContentHash()) && !hash.equals(proposal.getInitialContentHash())) {
            return "content_mismatch";
        }
        return null;
    }

    private ChangeProposal failed(UUID id, String code, String message) {
        LOG.warn("Proposal {} not applied: {}", id, code);
        return finished(store.markFailed(id, code, message), "FAILED");
    }

    private static ChangeProposal finished(ChangeProposal proposal, String state) {
        SafeMetrics.count("dynamic.ai.agent.proposals", "state", state, "kind", kind(proposal), "origin", "-");
        return proposal;
    }

    private static String kind(ChangeProposal proposal) {
        return proposal.getChangeKind().name();
    }

    /** Whether the host failed on an optimistic lock (JPA, Hibernate or Spring's translation of them). */
    static boolean isOptimisticLock(Throwable failure) {
        int depth = 0;
        for (Throwable t = failure; t != null && depth < 16; t = t.getCause() == t ? null : t.getCause(), depth++) {
            String name = t.getClass().getSimpleName();
            if (name.equals("OptimisticLockException") || name.equals("StaleObjectStateException")
                    || name.equals("StaleStateException") || name.equals("OptimisticLockingFailureException")
                    || name.equals("ObjectOptimisticLockingFailureException")) {
                return true;
            }
        }
        return false;
    }
}
