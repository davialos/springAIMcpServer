package com.springaimcpservercommon.persistence.config;

import org.jspecify.annotations.Nullable;

/**
 * Outcome of an operation that produced a new snapshot generation.
 *
 * @param generation           the new generation (previous + 1)
 * @param manifestHash         its manifest hash, see {@link SnapshotManifest}
 * @param entryCount           number of live resources in the generation
 * @param rollbackOfGeneration target generation when this was a rollback
 */
public record PublishResult(long generation, String manifestHash, int entryCount, @Nullable Long rollbackOfGeneration) {
}
