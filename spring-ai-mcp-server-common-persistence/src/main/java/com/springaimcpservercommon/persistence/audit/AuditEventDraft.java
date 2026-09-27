package com.springaimcpservercommon.persistence.audit;

import com.springaimcpservercommon.persistence.support.Checks;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * An audit event to append (standard tier, ADR-0018: actors, decisions, ids, counts and hashes — never prompt or row
 * content). The chain, sequence number, hashes, id and environment are assigned by {@link AuditTrail}. The compact
 * constructor mirrors the {@code ck_audit_event_*} constraints and canonicalises {@code detailsJson}.
 *
 * @param category         category
 * @param action           action code, {@code ^[A-Z][A-Z0-9_]{2,63}$} (e.g. {@code TOOL_INVOKED})
 * @param plane            plane
 * @param actorId          acting principal; {@code null} exactly when {@code actorType} is SYSTEM
 * @param actorType        actor type
 * @param onBehalfOfId     principal the actor acted for, if any
 * @param workspaceId      workspace (selects the hash chain); {@code null} = system chain
 * @param resourceType     type of the affected resource, if any
 * @param resourceId       id of the affected resource, if any
 * @param decision         authorization decision
 * @param reason           reason; required for DENY
 * @param traceId          trace id, if any
 * @param turnId           agent turn, if any
 * @param toolInvocationId tool invocation, if any
 * @param proposalId       change proposal, if any
 * @param mcpSessionId     MCP session, if any
 * @param detailsJson      JSON object with further non-sensitive details, if any
 * @param occurredAt       event time; {@code null} = the trail's clock at append time
 */
public record AuditEventDraft(
        AuditCategory category,
        String action,
        AuditPlane plane,
        @Nullable UUID actorId,
        AuditActorType actorType,
        @Nullable UUID onBehalfOfId,
        @Nullable UUID workspaceId,
        @Nullable String resourceType,
        @Nullable String resourceId,
        AuditDecision decision,
        @Nullable String reason,
        @Nullable String traceId,
        @Nullable UUID turnId,
        @Nullable UUID toolInvocationId,
        @Nullable UUID proposalId,
        @Nullable UUID mcpSessionId,
        @Nullable String detailsJson,
        @Nullable Instant occurredAt) {

    /** Largest canonical details document accepted (characters). */
    public static final int MAX_DETAILS_LENGTH = 16_384;

    private static final Pattern ACTION = Pattern.compile("^[A-Z][A-Z0-9_]{2,63}$");

    /**
     * Validates and canonicalises the draft.
     */
    public AuditEventDraft {
        Objects.requireNonNull(category, "category");
        Checks.matches(action, ACTION, "action");
        Objects.requireNonNull(plane, "plane");
        Objects.requireNonNull(actorType, "actorType");
        if ((actorType == AuditActorType.SYSTEM) != (actorId == null)) {
            throw new IllegalArgumentException("actorId must be null exactly when actorType is SYSTEM");
        }
        Objects.requireNonNull(decision, "decision");
        resourceType = Checks.optionalText(resourceType, "resourceType", 128);
        resourceId = Checks.optionalText(resourceId, "resourceId", 512);
        reason = Checks.optionalText(reason, "reason", 1000);
        if (decision == AuditDecision.DENY && reason == null) {
            throw new IllegalArgumentException("a DENY decision needs a reason");
        }
        traceId = Checks.optionalText(traceId, "traceId", 128);
        detailsJson = Checks.optionalJsonObject(detailsJson, "detailsJson");
        if (detailsJson != null && detailsJson.length() > MAX_DETAILS_LENGTH) {
            throw new IllegalArgumentException("detailsJson must be at most " + MAX_DETAILS_LENGTH + " characters");
        }
    }

    /**
     * Minimal draft for an event by a principal; optional fields are {@code null}.
     *
     * @param category    category
     * @param action      action code
     * @param plane       plane
     * @param actorId     acting principal
     * @param actorType   actor type (not SYSTEM)
     * @param workspaceId workspace, or {@code null}
     * @param decision    decision
     * @param reason      reason (required for DENY)
     * @return the draft
     */
    public static AuditEventDraft of(AuditCategory category, String action, AuditPlane plane, UUID actorId,
                                     AuditActorType actorType, @Nullable UUID workspaceId, AuditDecision decision,
                                     @Nullable String reason) {
        return new AuditEventDraft(category, action, plane, actorId, actorType, null, workspaceId, null, null,
                decision, reason, null, null, null, null, null, null, null);
    }

    /**
     * Minimal system draft (no actor).
     *
     * @param action      action code
     * @param workspaceId workspace, or {@code null}
     * @param detailsJson details, or {@code null}
     * @return the draft
     */
    public static AuditEventDraft system(String action, @Nullable UUID workspaceId, @Nullable String detailsJson) {
        return new AuditEventDraft(AuditCategory.SYSTEM, action, AuditPlane.SYSTEM, null, AuditActorType.SYSTEM, null,
                workspaceId, null, null, AuditDecision.NOT_APPLICABLE, null, null, null, null, null, null, detailsJson,
                null);
    }
}
