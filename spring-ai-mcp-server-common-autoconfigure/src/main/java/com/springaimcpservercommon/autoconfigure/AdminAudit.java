package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.json.CanonicalJson;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.persistence.audit.AuditActorType;
import com.springaimcpservercommon.persistence.audit.AuditCategory;
import com.springaimcpservercommon.persistence.audit.AuditDecision;
import com.springaimcpservercommon.persistence.audit.AuditEventDraft;
import com.springaimcpservercommon.persistence.audit.AuditPlane;
import com.springaimcpservercommon.persistence.audit.AuditTrail;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Appends admin-plane audit events after a change has been committed. A failure to audit is logged
 * (without event content) and never fails the operator's action, because the change is already durable.
 */
@NullMarked
final class AdminAudit {

    private static final Logger LOG = LoggerFactory.getLogger(AdminAudit.class);

    private final AuditTrail trail;

    AdminAudit(AuditTrail trail) {
        this.trail = Objects.requireNonNull(trail, "trail");
    }

    /**
     * Records a permitted admin or data-write action.
     *
     * @param caller       acting principal
     * @param category     ADMIN or DATA_WRITE
     * @param plane        CONTROL or DATA
     * @param action       action code, {@code ^[A-Z][A-Z0-9_]{2,63}$}
     * @param workspaceId  workspace, if scoped
     * @param resourceType affected resource type
     * @param resourceId   affected resource id
     * @param proposalId   related change proposal, if any
     * @param reason       reason or comment, if any (max 1000 characters are stored)
     * @param details      small details object; must not contain secrets or row data
     */
    void record(DaiPrincipal caller, AuditCategory category, AuditPlane plane, String action,
                @Nullable UUID workspaceId, String resourceType, String resourceId, @Nullable UUID proposalId,
                @Nullable String reason, Map<String, Object> details) {
        try {
            String bounded = reason == null ? null : reason.length() > 1000 ? reason.substring(0, 1000) : reason;
            trail.append(new AuditEventDraft(category, action, plane, caller.principalId(),
                    AuditActorType.valueOf(caller.type().name()), null, workspaceId, resourceType, resourceId,
                    AuditDecision.PERMIT, bounded, null, null, null, proposalId, null,
                    details.isEmpty() ? null : CanonicalJson.write(details), null));
        } catch (RuntimeException e) {
            LOG.error("Audit append failed for {} ({} {})", action, resourceType, resourceId, e);
        }
    }
}
