package com.springaimcpservercommon.persistence.config;

import com.springaimcpservercommon.core.catalog.CatalogElementRef;

import java.util.UUID;

/**
 * A catalog element pinned by a revision, used for drift detection (LLD-03 §6): compare {@code signatureHash} with
 * the element's signature in the current scan.
 *
 * @param revisionId    pinning revision
 * @param resourceId    its resource
 * @param revisionState its state
 * @param elementRef    pinned element
 * @param signatureHash signature hash at authoring time
 */
public record PinnedReference(
        UUID revisionId,
        UUID resourceId,
        RevisionState revisionState,
        CatalogElementRef elementRef,
        String signatureHash) {
}
