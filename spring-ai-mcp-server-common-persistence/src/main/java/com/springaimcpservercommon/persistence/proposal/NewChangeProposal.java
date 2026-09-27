package com.springaimcpservercommon.persistence.proposal;

import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.invocation.Channel;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * A proposal to create (LLD-11 §3). The content hash is computed by the proposing layer over target and changes; a
 * confirm must present the same hash.
 *
 * @param workspaceId         workspace
 * @param origin              where it came from
 * @param channel             entry channel
 * @param conversationId      conversation, if any
 * @param turnId              agent turn, if any
 * @param toolInvocationId    tool invocation that proposed it, if any
 * @param mcpSessionId        MCP session, if any
 * @param ownerId             principal who owns (and alone may confirm) the proposal
 * @param targetKind          how it is applied
 * @param targetRef           target: {@code op:} for HOST_OPERATION, {@code entity:} for ENTITY_WRITE
 * @param targetArgsJson      operation arguments as JSON, if any
 * @param changeKind          kind of change
 * @param approvalRequirement approval policy
 * @param requiredApprovals   0 for SELF_CONFIRM, 1–5 otherwise
 * @param contentHash         {@code sha256:} over target and changes
 * @param summary             human-readable summary (no sensitive values)
 * @param validationJson      validation report as JSON, if any
 * @param idempotencyKey      client idempotency key, if any (unique per owner)
 * @param timeToLive          time until the proposal expires if not confirmed (and approved)
 * @param retention           how long the proposal is kept after its expiry time; terminal transitions may extend it
 * @param records             the records touched, in order (1–{@value ChangeProposal#MAX_RECORDS})
 */
public record NewChangeProposal(
        UUID workspaceId,
        ProposalOrigin origin,
        Channel channel,
        @Nullable UUID conversationId,
        @Nullable UUID turnId,
        @Nullable UUID toolInvocationId,
        @Nullable UUID mcpSessionId,
        UUID ownerId,
        ProposalTargetKind targetKind,
        CatalogElementRef targetRef,
        @Nullable String targetArgsJson,
        ChangeKind changeKind,
        ApprovalRequirement approvalRequirement,
        int requiredApprovals,
        String contentHash,
        String summary,
        @Nullable String validationJson,
        @Nullable String idempotencyKey,
        Duration timeToLive,
        Duration retention,
        List<NewProposalRecord> records) {

    /**
     * Copies the record list.
     */
    public NewChangeProposal {
        records = List.copyOf(records);
    }
}
