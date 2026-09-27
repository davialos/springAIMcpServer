/**
 * Catalog model (LLD-02 §3.3, LLD-03 §2): element references, scanned descriptors, the effective catalog produced
 * by policy merging, the {@link com.springaimcpservercommon.core.catalog.MetadataRegistry} port and the
 * {@link com.springaimcpservercommon.core.catalog.EntityCatalogSource} SPI implemented by the query module.
 *
 * <p>All types are immutable values; collections are defensively copied and deterministically ordered.
 */
@NullMarked
package com.springaimcpservercommon.core.catalog;

import org.jspecify.annotations.NullMarked;
