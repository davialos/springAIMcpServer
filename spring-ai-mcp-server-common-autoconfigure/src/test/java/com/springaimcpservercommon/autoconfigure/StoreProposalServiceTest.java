package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.tool.ProposalService.Change;
import com.springaimcpservercommon.ai.tool.ProposalService.ProposalRefusedException;
import com.springaimcpservercommon.ai.tool.ProposalService.ProposalRequest;
import com.springaimcpservercommon.ai.tool.ResultPolicy;
import com.springaimcpservercommon.ai.tool.ToolBinding;
import com.springaimcpservercommon.ai.tool.ToolCallScope;
import com.springaimcpservercommon.ai.tool.ToolSource;
import com.springaimcpservercommon.ai.tool.WriteMode;
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
import com.springaimcpservercommon.persistence.proposal.ProposalOrigin;
import com.springaimcpservercommon.persistence.proposal.ProposalState;
import com.springaimcpservercommon.persistence.proposal.ProposalTargetKind;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoreProposalServiceTest {

    private static final CatalogElementRef ORDER = CatalogElementRef.entity("com.acme.Order");
    private static final CatalogElementRef UPDATE_OP = CatalogElementRef.operation("com.acme.OrderService",
            "update", List.of());
    private static final UUID TURN = UUID.randomUUID();
    private static final DaiProperties.Write SETTINGS = new DaiProperties.Write(Duration.ofDays(7), true,
            Duration.ofMinutes(15), false, 8);
    private final DaiPrincipal alice = new DaiPrincipal(UUID.randomUUID(), SubjectType.USER, "local", "alice", "Alice",
            Set.of(), Set.of(), Map.of(), Map.of(), Classification.INTERNAL, null, Set.of());

    private static EffectiveCatalog catalog(boolean readOnly, CatalogElementRef entity) {
        OperationDescriptor descriptor = new OperationDescriptor(UPDATE_OP, "orderService", "com.acme.OrderService",
                "com.acme.OrderService", "update", List.of(), "update_order",
                "Updates an order", List.of(), List.of(), JsonSchema.emptyObject(), null, "void", readOnly, false,
                Classification.INTERNAL, new ResultBounding(ResultBounding.Kind.NOT_A_LIST, null), null, entity);
        EffectiveOperation operation = new EffectiveOperation(UPDATE_OP, descriptor, "update_order",
                "Updates an order", List.of(), readOnly, Classification.INTERNAL, 100, true, List.of());
        return new EffectiveCatalog(1, "sha256:" + "0".repeat(64), "sha256:" + "0".repeat(64), Map.of(),
                Map.of(UPDATE_OP, operation), List.of(), List.of());
    }

    private static ToolBinding binding(ToolSource source, Change change) {
        return new ToolBinding(UUID.randomUUID(), 1, UUID.randomUUID(), "update_order", source, null, Map.of(),
                WriteMode.PROPOSE, false, Duration.ofSeconds(5), 3, ResultPolicy.DEFAULT, false, change);
    }

    private ProposalRequest request(ToolBinding binding, String input, ToolCallScope scope) {
        return new ProposalRequest(binding, input, alice, scope, UUID.randomUUID());
    }

    private ProposalRequest request(Change change, String input) {
        return request(binding(new ToolSource.OperationSource(UPDATE_OP), change), input,
                ToolCallScope.ofTurn(Channel.CHAT, TURN));
    }

    private static void assertRefused(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ProposalRefusedException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void aToolCallBecomesAProposalThatPassesTheStoresValidation() {
        ProposalRequest request = request(Change.UPDATE, "{\"status\":\"SHIPPED\",\"id\":101}");

        NewChangeProposal data = StoreProposalService.toNewProposal(request, catalog(false, ORDER), SETTINGS);
        ChangeProposal proposal = ChangeProposal.propose(data, Instant.parse("2026-09-29T10:00:00Z"));

        assertThat(proposal.getState()).isEqualTo(ProposalState.PROPOSED);
        assertThat(proposal.getOwnerId()).isEqualTo(alice.principalId());
        assertThat(proposal.getWorkspaceId()).isEqualTo(request.binding().workspaceId());
        assertThat(data.origin()).isEqualTo(ProposalOrigin.AGENT_TOOL);
        assertThat(data.channel()).isEqualTo(Channel.CHAT);
        assertThat(data.turnId()).isEqualTo(TURN);
        assertThat(data.toolInvocationId()).isEqualTo(request.toolInvocationId());
        assertThat(data.targetKind()).isEqualTo(ProposalTargetKind.HOST_OPERATION);
        assertThat(data.targetRef()).isEqualTo(UPDATE_OP);
        assertThat(data.changeKind()).isEqualTo(ChangeKind.UPDATE);
        assertThat(data.approvalRequirement()).isEqualTo(ApprovalRequirement.SELF_CONFIRM);
        assertThat(data.requiredApprovals()).isZero();
        assertThat(data.timeToLive()).isEqualTo(Duration.ofMinutes(15));
        assertThat(data.retention()).isEqualTo(Duration.ofDays(7));
        assertThat(data.targetArgsJson()).isEqualTo("{\"id\":101,\"status\":\"SHIPPED\"}");
        assertThat(data.records()).singleElement().satisfies(r -> {
            assertThat(r.entityRef()).isEqualTo(ORDER);
            assertThat(r.afterValuesJson()).isEqualTo("{\"id\":101,\"status\":\"SHIPPED\"}");
            assertThat(r.beforeValuesJson()).isNull();
        });
        assertThat(data.summary()).doesNotContain("SHIPPED").contains("update_order");
    }

    @Test
    void theContentHashIgnoresKeyOrderAndChangesWithTheArguments() {
        var catalog = catalog(false, ORDER);
        String a = StoreProposalService.toNewProposal(request(Change.UPDATE, "{\"a\":1,\"b\":2}"), catalog, SETTINGS)
                .contentHash();
        String reordered = StoreProposalService.toNewProposal(request(Change.UPDATE, "{\"b\":2,\"a\":1}"), catalog,
                SETTINGS).contentHash();
        String other = StoreProposalService.toNewProposal(request(Change.UPDATE, "{\"a\":1,\"b\":3}"), catalog,
                SETTINGS).contentHash();

        assertThat(reordered).isEqualTo(a);
        assertThat(other).isNotEqualTo(a);
    }

    @Test
    void theSameCallInTheSameTurnHasTheSameIdempotencyKey() {
        var catalog = catalog(false, ORDER);
        ToolBinding binding = binding(new ToolSource.OperationSource(UPDATE_OP), Change.UPDATE);
        ToolCallScope scope = ToolCallScope.ofTurn(Channel.CHAT, TURN);

        String first = StoreProposalService.toNewProposal(request(binding, "{\"a\":1}", scope), catalog, SETTINGS)
                .idempotencyKey();
        String repeated = StoreProposalService.toNewProposal(request(binding, "{\"a\":1}", scope), catalog, SETTINGS)
                .idempotencyKey();
        String otherTurn = StoreProposalService.toNewProposal(request(binding, "{\"a\":1}",
                ToolCallScope.ofTurn(Channel.CHAT, UUID.randomUUID())), catalog, SETTINGS).idempotencyKey();

        assertThat(repeated).isEqualTo(first);
        assertThat(otherTurn).isNotEqualTo(first);
        assertThat(first).hasSizeLessThanOrEqualTo(255);
    }

    @Test
    void anMcpCallIsAnMcpToolProposal() {
        UUID mcpRequest = UUID.randomUUID();
        NewChangeProposal data = StoreProposalService.toNewProposal(request(
                binding(new ToolSource.OperationSource(UPDATE_OP), Change.UPDATE), "{}",
                ToolCallScope.ofMcpRequest(mcpRequest)), catalog(false, ORDER), SETTINGS);

        assertThat(data.origin()).isEqualTo(ProposalOrigin.MCP_TOOL);
        assertThat(data.channel()).isEqualTo(Channel.MCP);
        assertThat(data.turnId()).isNull();
        assertThat(data.idempotencyKey()).startsWith(mcpRequest.toString());
        ChangeProposal.propose(data, Instant.now());
    }

    @Test
    void aDeleteAlwaysNeedsAnApproverAndTheSwitchMakesEveryProposalNeedOne() {
        var catalog = catalog(false, ORDER);
        NewChangeProposal delete = StoreProposalService.toNewProposal(request(Change.DELETE, "{}"), catalog, SETTINGS);
        assertThat(delete.changeKind()).isEqualTo(ChangeKind.DELETE);
        assertThat(delete.approvalRequirement()).isEqualTo(ApprovalRequirement.SELF_CONFIRM_PLUS_APPROVER);
        assertThat(delete.requiredApprovals()).isEqualTo(1);

        var strict = new DaiProperties.Write(Duration.ofDays(7), true, Duration.ofMinutes(15), true, 8);
        NewChangeProposal update = StoreProposalService.toNewProposal(request(Change.CREATE, "{}"), catalog, strict);
        assertThat(update.changeKind()).isEqualTo(ChangeKind.CREATE);
        assertThat(update.approvalRequirement()).isEqualTo(ApprovalRequirement.SELF_CONFIRM_PLUS_APPROVER);
        ChangeProposal.propose(delete, Instant.now());
        ChangeProposal.propose(update, Instant.now());
    }

    @Test
    void anUnspecifiedChangeKindMeansUpdate() {
        assertThat(StoreProposalService.toNewProposal(request((Change) null, "{}"), catalog(false, ORDER), SETTINGS)
                .changeKind()).isEqualTo(ChangeKind.UPDATE);
    }

    @Test
    void thingsThatCannotBeReviewedAreRefusedWithAStableCode() {
        var writable = catalog(false, ORDER);
        assertRefused(() -> StoreProposalService.toNewProposal(request(Change.UPDATE, "{}"), catalog(true, ORDER),
                SETTINGS), "not_a_write_tool");
        assertRefused(() -> StoreProposalService.toNewProposal(request(Change.UPDATE, "{}"), catalog(false, null),
                SETTINGS), "operation_without_entity");
        assertRefused(() -> StoreProposalService.toNewProposal(request(binding(new ToolSource.OperationSource(
                        CatalogElementRef.operation("com.acme.Other", "gone", List.of())), Change.UPDATE), "{}",
                ToolCallScope.ofTurn(Channel.CHAT, TURN)), writable, SETTINGS), "operation_unavailable");
        assertRefused(() -> StoreProposalService.toNewProposal(request(binding(new ToolSource.QuerySource(
                UUID.randomUUID()), Change.UPDATE), "{}", ToolCallScope.ofTurn(Channel.CHAT, TURN)), writable,
                SETTINGS), "not_a_write_tool");
        assertRefused(() -> StoreProposalService.toNewProposal(request(binding(new ToolSource.OperationSource(
                UPDATE_OP), Change.UPDATE), "{}", new ToolCallScope(Channel.ENDPOINT, null, null)), writable,
                SETTINGS), "proposal_unavailable");
        assertRefused(() -> StoreProposalService.toNewProposal(request(Change.UPDATE, "[1]"), writable, SETTINGS),
                "invalid_arguments");
        assertRefused(() -> StoreProposalService.toNewProposal(request(Change.UPDATE, "{oops"), writable, SETTINGS),
                "invalid_arguments");
    }

    @Test
    void writeSettingsAreValidatedAndOffByDefault() {
        assertThatThrownBy(() -> new DaiProperties.Write(Duration.ofDays(7), false, Duration.ofSeconds(5), false, 8))
                .hasMessageContaining("proposal-ttl");
        assertThat(new DaiProperties.Write(Duration.ofDays(7), false, Duration.ofMinutes(15), false, 8).enabled())
                .isFalse();
    }
}
