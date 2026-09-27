/**
 * Configuration resources, immutable revisions, reviews, published snapshots, cluster node state, kill switches
 * and grants (LLD-09, V2 migration).
 *
 * <p>{@link com.springaimcpservercommon.persistence.config.ConfigStore} is the single write path for the revision
 * lifecycle and snapshot generations; every change of the live set produces a new, contiguous generation with a
 * full manifest in one transaction (ADR-0006).
 */
@NullMarked
package com.springaimcpservercommon.persistence.config;

import org.jspecify.annotations.NullMarked;
