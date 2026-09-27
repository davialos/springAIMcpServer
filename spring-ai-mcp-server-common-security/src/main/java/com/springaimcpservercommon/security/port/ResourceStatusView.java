package com.springaimcpservercommon.security.port;

import java.util.UUID;

/**
 * Port to the publication state of a resource ({@code dai_resource.status} + published revision), served from the
 * loaded snapshot rather than per-call queries.
 */
public interface ResourceStatusView {

    /**
     * Returns the state of a resource.
     *
     * @param resourceId resource id
     * @return the state ({@link ResourceStatus#UNKNOWN} if not found)
     */
    ResourceStatus statusOf(UUID resourceId);

    /** Publication state of a resource. */
    enum ResourceStatus {
        /** Active with a published revision in the current snapshot. */
        PUBLISHED,
        /** Active but without a published revision (drafts only). */
        NOT_PUBLISHED,
        /** Suspended (operator action or availability probe). */
        SUSPENDED,
        /** Retired. */
        RETIRED,
        /** Not found. */
        UNKNOWN
    }
}
