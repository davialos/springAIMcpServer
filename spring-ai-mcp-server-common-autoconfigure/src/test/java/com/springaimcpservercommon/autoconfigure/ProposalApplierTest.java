package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.catalog.EffectiveOperation;
import com.springaimcpservercommon.core.catalog.JsonSchema;
import com.springaimcpservercommon.core.catalog.OperationDescriptor;
import com.springaimcpservercommon.core.catalog.ResultBounding;
import com.springaimcpservercommon.core.invocation.Channel;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.core.principal.SubjectType;
import com.springaimcpservercommon.persistence.proposal.ApprovalRequirement;
import com.springaimcpservercommon.persistence.proposal.ChangeKind;
import com.springaimcpservercommon.persistence.proposal.ChangeProposal;
import com.springaimcpservercommon.persistence.proposal.NewChangeProposal;
import com.springaimcpservercommon.persistence.proposal.NewProposalRecord;
import com.springaimcpservercommon.persistence.proposal.ProposalOrigin;
import com.springaimcpservercommon.persistence.proposal.ProposalRuleViolationException;
import com.springaimcpservercommon.persistence.proposal.ProposalState;
import com.springaimcpservercommon.persistence.proposal.ProposalTargetKind;
import com.springaimcpservercommon.security.permission.Permission;
import com.springaimcpservercommon.webmvc.endpoint.GenericDynamicHandler.BackingException;
import com.springaimcpservercommon.webmvc.problem.ProblemCode;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProposalApplierTest {

    private static final Instant T0 = Instant.parse("2026-09-29T10:00:00Z");
    private static final CatalogElementRef ORDER = CatalogElementRef.entity("com.acme.Order");
    private static final CatalogElementRef OP = CatalogElementRef.operation("com.acme.OrderService", "update",
            List.of());
    private static final DaiProperties.Write SETTINGS = new DaiProperties.Write(Duration.ofDays(7), true,
            Duration.ofMinutes(15), false, 8);

    private final DaiPrincipal alice = principal();
    private final Map<UUID, ChangeProposal> proposals = new HashMap<>();
    private final List<String> transitions = new ArrayList<>();
    private final List<DaiPrincipal> executedAs = new ArrayList<>();
    private final List<Map<String, Object>> executedArgs = new ArrayList<>();
    private Clock clock = Clock.fixed(T0.plusSeconds(60), ZoneOffset.UTC);
    private boolean permitted = true;
    private ThrowingHandler handler = (op, args, p) -> "{}";
    private EffectiveCatalog catalog = catalog(false, true);

    /** Stands in for Hibernate's exception of the same simple name (the check goes by name, no dependency). */
    static final class StaleObjectStateException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    @FunctionalInterface
    interface ThrowingHandler {
        String run(CatalogElementRef op, Map<String, Object> args, DaiPrincipal principal) throws BackingException;
    }

    private static DaiPrincipal principal() {
        return new DaiPrincipal(UUID.randomUUID(), SubjectType.USER, "local", "alice", "Alice", Set.of(), Set.of(),
                Map.of(), Map.of(), Classification.INTERNAL, null, Set.of());
    }

    private static EffectiveCatalog catalog(boolean readOnly, boolean present) {
        OperationDescriptor descriptor = new OperationDescriptor(OP, "orderService", "com.acme.OrderService",
                "com.acme.OrderService", "update", List.of(), "update_order", "Updates", List.of(), List.of(),
                JsonSchema.emptyObject(), null, "void", readOnly, false, Classification.INTERNAL,
                new ResultBounding(ResultBounding.Kind.NOT_A_LIST, null), null, ORDER);
        EffectiveOperation op = new EffectiveOperation(OP, descriptor, "update_order", "Updates", List.of(), readOnly,
                Classification.INTERNAL, 100, true, List.of());
        return new EffectiveCatalog(1, "sha256:" + "0".repeat(64), "sha256:" + "0".repeat(64), Map.of(),
                present ? Map.of(OP, op) : Map.of(), List.of(), List.of());
    }

    /** A CONFIRMED proposal owned by {@code alice}; {@code hashOf} is what the stored hash is computed over. */
    private ChangeProposal confirmed(String argsJson, String hashedArgsJson) {
        Map<String, Object> hashed = StoreProposalService.arguments(hashedArgsJson);
        NewChangeProposal data = new NewChangeProposal(UUID.randomUUID(), ProposalOrigin.AGENT_TOOL, Channel.CHAT,
                null, UUID.randomUUID(), UUID.randomUUID(), null, alice.principalId(),
                ProposalTargetKind.HOST_OPERATION, OP, argsJson, ChangeKind.UPDATE, ApprovalRequirement.SELF_CONFIRM,
                0, StoreProposalService.contentHash(OP, ChangeKind.UPDATE, hashed), "summary", null, null,
                Duration.ofMinutes(15), Duration.ofDays(7),
                List.of(new NewProposalRecord(ORDER, null, null, argsJson, null, null)));
        ChangeProposal proposal = ChangeProposal.propose(data, T0);
        assertThat(proposal.confirm(alice.principalId(), proposal.getContentHash(), T0.plusSeconds(30))).isTrue();
        proposals.put(proposal.getId(), proposal);
        return proposal;
    }

    private ChangeProposal confirmed() {
        return confirmed("{\"status\":\"SHIPPED\"}", "{\"status\":\"SHIPPED\"}");
    }

    private ProposalApplier applier(DaiProperties.Write settings) {
        ProposalApplier.Proposals fake = new ProposalApplier.Proposals() {
            @Override
            public Optional<ChangeProposal> findForOwner(UUID id, UUID ownerId) {
                return Optional.ofNullable(proposals.get(id)).filter(p -> p.getOwnerId().equals(ownerId));
            }

            @Override
            public ChangeProposal markApplying(UUID id) {
                transitions.add("APPLYING");
                proposals.get(id).markApplying(clock.instant());
                return proposals.get(id);
            }

            @Override
            public ChangeProposal markApplied(UUID id, String ref) {
                transitions.add("APPLIED");
                proposals.get(id).markApplied(ref, clock.instant());
                return proposals.get(id);
            }

            @Override
            public ChangeProposal markConflict(UUID id, String code, String message) {
                transitions.add("CONFLICT:" + code);
                proposals.get(id).markConflict(code, message, clock.instant());
                return proposals.get(id);
            }

            @Override
            public ChangeProposal markFailed(UUID id, String code, String message) {
                transitions.add("FAILED:" + code);
                proposals.get(id).markFailed(code, message, clock.instant());
                return proposals.get(id);
            }
        };
        return new ProposalApplier(fake, () -> catalog, () -> (op, args, p) -> {
            executedAs.add(p);
            executedArgs.add(args);
            return handler.run(op, args, p);
        }, (caller, permission, ws) -> permitted && permission == Permission.DATA_WRITE_CONFIRM, settings,
                Clock.fixed(clock.instant(), ZoneOffset.UTC));
    }

    private ProposalApplier applier() {
        return applier(SETTINGS);
    }

    @Test
    void aConfirmedProposalIsAppliedAsItsOwnerWithTheStoredArguments() {
        ChangeProposal proposal = confirmed();

        ProposalApplier.Result result = applier().apply(proposal.getId(), alice);

        assertThat(result.attempted()).isTrue();
        assertThat(result.proposal().getState()).isEqualTo(ProposalState.APPLIED);
        assertThat(transitions).containsExactly("APPLYING", "APPLIED");
        assertThat(executedAs).containsExactly(alice);
        assertThat(executedArgs).containsExactly(Map.of("status", "SHIPPED"));
    }

    @Test
    void anAlreadyAppliedProposalIsReturnedWithoutRunningAgain() {
        ChangeProposal proposal = confirmed();
        ProposalApplier applier = applier();
        applier.apply(proposal.getId(), alice);

        ProposalApplier.Result again = applier.apply(proposal.getId(), alice);

        assertThat(again.attempted()).isFalse();
        assertThat(again.proposal().getState()).isEqualTo(ProposalState.APPLIED);
        assertThat(executedAs).hasSize(1);
    }

    @Test
    void onlyTheOwnerCanApplyAndOnlyAConfirmedProposal() {
        ChangeProposal proposal = confirmed();
        assertThatThrownBy(() -> applier().apply(proposal.getId(), principal()))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> applier().apply(UUID.randomUUID(), alice)).isInstanceOf(NoSuchElementException.class);

        NewChangeProposal pendingData = new NewChangeProposal(UUID.randomUUID(), ProposalOrigin.AGENT_TOOL,
                Channel.CHAT, null, UUID.randomUUID(), null, null, alice.principalId(),
                ProposalTargetKind.HOST_OPERATION, OP, "{}", ChangeKind.UPDATE, ApprovalRequirement.SELF_CONFIRM, 0,
                StoreProposalService.contentHash(OP, ChangeKind.UPDATE, Map.of()), "s", null, null,
                Duration.ofMinutes(15), Duration.ofDays(7),
                List.of(new NewProposalRecord(ORDER, null, null, "{}", null, null)));
        ChangeProposal pending = ChangeProposal.propose(pendingData, T0);
        proposals.put(pending.getId(), pending);
        assertThatThrownBy(() -> applier().apply(pending.getId(), alice))
                .isInstanceOfSatisfying(ProposalRuleViolationException.class,
                        e -> assertThat(e.reason()).isEqualTo(ProposalRuleViolationException.Reason.ILLEGAL_TRANSITION));
        assertThat(executedAs).isEmpty();
    }

    @Test
    void nothingRunsWhenWritesAreOffOrThePermissionIsGone() {
        ChangeProposal proposal = confirmed();
        var off = new DaiProperties.Write(Duration.ofDays(7), false, Duration.ofMinutes(15), false, 8);
        assertThatThrownBy(() -> applier(off).apply(proposal.getId(), alice))
                .isInstanceOfSatisfying(ProposalApplier.ApplyRefusedException.class,
                        e -> assertThat(e.code()).isEqualTo("writes_disabled"));
        permitted = false;
        assertThatThrownBy(() -> applier().apply(proposal.getId(), alice))
                .isInstanceOfSatisfying(ProposalApplier.ApplyRefusedException.class,
                        e -> assertThat(e.code()).isEqualTo("access_denied"));
        assertThat(executedAs).isEmpty();
        assertThat(proposal.getState()).isEqualTo(ProposalState.CONFIRMED);
    }

    @Test
    void anExpiredProposalIsFailedAndNeverRun() {
        ChangeProposal proposal = confirmed();
        clock = Clock.fixed(T0.plus(Duration.ofHours(1)), ZoneOffset.UTC);

        assertThatThrownBy(() -> applier().apply(proposal.getId(), alice))
                .isInstanceOfSatisfying(ProposalRuleViolationException.class,
                        e -> assertThat(e.reason()).isEqualTo(ProposalRuleViolationException.Reason.EXPIRED));
        assertThat(transitions).containsExactly("FAILED:expired");
        assertThat(executedAs).isEmpty();
    }

    @Test
    void anOperationThatIsGoneOrReadOnlyIsFailedBeforeRunning() {
        ChangeProposal gone = confirmed();
        catalog = catalog(false, false);
        assertThat(applier().apply(gone.getId(), alice).proposal().getFailureCode()).isEqualTo("operation_unavailable");

        ChangeProposal readOnly = confirmed();
        catalog = catalog(true, true);
        assertThat(applier().apply(readOnly.getId(), alice).proposal().getFailureCode())
                .isEqualTo("operation_unavailable");
        assertThat(executedAs).isEmpty();
    }

    @Test
    void contentThatNoLongerMatchesWhatWasConfirmedIsRefused() {
        ChangeProposal tampered = confirmed("{\"status\":\"DELETED\"}", "{\"status\":\"SHIPPED\"}");

        ProposalApplier.Result result = applier().apply(tampered.getId(), alice);

        assertThat(result.proposal().getState()).isEqualTo(ProposalState.FAILED);
        assertThat(result.proposal().getFailureCode()).isEqualTo("content_mismatch");
        assertThat(executedAs).isEmpty();
    }

    @Test
    void aHostFailureIsRecordedWithAFixedMessageNeverTheHostsOwn() {
        ChangeProposal proposal = confirmed();
        handler = (op, args, p) -> {
            throw new BackingException(ProblemCode.EXECUTION_ERROR, "jdbc:postgresql://secret-host row 42");
        };

        ChangeProposal result = applier().apply(proposal.getId(), alice).proposal();

        assertThat(result.getState()).isEqualTo(ProposalState.FAILED);
        assertThat(result.getFailureCode()).isEqualTo("execution_error");
        assertThat(result.getFailureMessage()).doesNotContain("secret-host").doesNotContain("42");
    }

    @Test
    void anUnexpectedRuntimeFailureIsAlsoFailedWithoutLeaking() {
        ChangeProposal proposal = confirmed();
        handler = (op, args, p) -> {
            throw new IllegalStateException("NPE at row 42");
        };

        ChangeProposal result = applier().apply(proposal.getId(), alice).proposal();

        assertThat(result.getState()).isEqualTo(ProposalState.FAILED);
        assertThat(result.getFailureMessage()).doesNotContain("42");
    }

    @Test
    void anAccessDeniedFromTheHostIsFailedAsAccessDenied() {
        ChangeProposal proposal = confirmed();
        handler = (op, args, p) -> {
            throw new BackingException(ProblemCode.ACCESS_DENIED, "denied");
        };

        assertThat(applier().apply(proposal.getId(), alice).proposal().getFailureCode()).isEqualTo("access_denied");
    }

    @Test
    void anOptimisticLockFailureInTheHostIsAConflict() {
        ChangeProposal proposal = confirmed();
        handler = (op, args, p) -> {
            throw new BackingException(ProblemCode.EXECUTION_ERROR, "stale", new OptimisticLockException("v5 vs v6"));
        };

        ChangeProposal result = applier().apply(proposal.getId(), alice).proposal();

        assertThat(result.getState()).isEqualTo(ProposalState.CONFLICT);
        assertThat(result.getFailureCode()).isEqualTo("version_conflict");
        assertThat(ProposalApplier.isOptimisticLock(new RuntimeException(new IllegalStateException(
                new StaleObjectStateException())))).isTrue();
        assertThat(ProposalApplier.isOptimisticLock(new IllegalStateException("other"))).isFalse();
    }

    @Test
    void onlyTheConfiguredNumberOfAppliesRunAtOnce() throws Exception {
        var single = new DaiProperties.Write(Duration.ofDays(7), true, Duration.ofMinutes(15), false, 1);
        ChangeProposal first = confirmed();
        ChangeProposal second = confirmed();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        handler = (op, args, p) -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "{}";
        };
        ProposalApplier applier = applier(single);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ProposalApplier.Result> running = pool.submit(() -> applier.apply(first.getId(), alice));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> applier.apply(second.getId(), alice))
                    .isInstanceOfSatisfying(ProposalApplier.ApplyRefusedException.class,
                            e -> assertThat(e.code()).isEqualTo("apply_busy"));
            assertThat(second.getState()).isEqualTo(ProposalState.CONFIRMED);

            release.countDown();
            assertThat(running.get(10, TimeUnit.SECONDS).proposal().getState()).isEqualTo(ProposalState.APPLIED);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
