package com.springaimcpservercommon.persistence.identity;

import com.springaimcpservercommon.core.principal.SubjectType;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable view of a {@code dai_principal} row.
 *
 * @param id          principal id
 * @param subjectType kind of subject
 * @param issuer      IdP issuer or {@code dai}
 * @param externalId  stable external subject id
 * @param displayName optional display name
 * @param status      current status
 * @param firstSeenAt when the subject was first resolved
 * @param lastSeenAt  when the subject was last seen (throttled, see {@link PrincipalDirectory})
 */
public record PrincipalView(
        UUID id,
        SubjectType subjectType,
        String issuer,
        String externalId,
        @Nullable String displayName,
        PrincipalStatus status,
        Instant firstSeenAt,
        Instant lastSeenAt) {
}
