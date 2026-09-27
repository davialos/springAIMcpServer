package com.springaimcpservercommon.persistence.proposal;

import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.hash.Sha256;
import com.springaimcpservercommon.core.invocation.Channel;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Builders of proposal test data. */
public final class ProposalFixtures {

    /** Content hash used by {@link #update}. */
    public static final String HASH = Sha256.of("content-v1");

    private ProposalFixtures() {
    }

    /**
     * A single-record UPDATE proposal via a host operation.
     *
     * @param workspaceId    workspace
     * @param ownerId        owner
     * @param requirement    approval requirement
     * @param approvals      required approvals
     * @param idempotencyKey key or null
     * @return proposal data
     */
    public static NewChangeProposal update(UUID workspaceId, UUID ownerId, ApprovalRequirement requirement,
                                           int approvals, String idempotencyKey) {
        return new NewChangeProposal(workspaceId, ProposalOrigin.AGENT_TOOL, Channel.CHAT, null, UUID.randomUUID(),
                null, null, ownerId, ProposalTargetKind.HOST_OPERATION,
                CatalogElementRef.parse("op:com.acme.order.OrderService#ship(java.lang.Long)"), "{\"id\":101}",
                ChangeKind.UPDATE, requirement, approvals, HASH, "Ship order 101", null, idempotencyKey,
                Duration.ofMinutes(15), Duration.ofDays(7),
                List.of(new NewProposalRecord(CatalogElementRef.entity("com.acme.order.Order"), "101",
                        "{\"status\":\"PAID\"}", "{\"status\":\"SHIPPED\"}", BaseVersionKind.JPA_VERSION, "7")));
    }

    /**
     * Same as {@link #update} without idempotency key and with self-confirmation.
     *
     * @param workspaceId workspace
     * @param ownerId     owner
     * @return proposal data
     */
    public static NewChangeProposal update(UUID workspaceId, UUID ownerId) {
        return update(workspaceId, ownerId, ApprovalRequirement.SELF_CONFIRM, 0, null);
    }
}
