package com.springaimcpservercommon.persistence.config;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A loaded snapshot generation: the full live set with specs, as consumed by each node's snapshot watcher
 * (LLD-09 §4). Nodes must verify {@link #isIntact()} before swapping it in and discard the whole generation otherwise.
 *
 * @param generation           generation number
 * @param publishedAt          publish time
 * @param publishedBy          publishing principal
 * @param reason               optional reason
 * @param rollbackOfGeneration target generation when it was a rollback
 * @param manifestHash         stored manifest hash
 * @param resources            live resources, ordered by resource id text
 */
public record PublishedSnapshot(
        long generation,
        Instant publishedAt,
        UUID publishedBy,
        @Nullable String reason,
        @Nullable Long rollbackOfGeneration,
        String manifestHash,
        List<PublishedResource> resources) {

    /** Copies the resource list. */
    public PublishedSnapshot {
        resources = List.copyOf(resources);
    }

    /**
     * Resource id → revision id of this generation.
     *
     * @return the manifest entries
     */
    public Map<UUID, UUID> manifest() {
        Map<UUID, UUID> entries = new LinkedHashMap<>();
        resources.forEach(r -> entries.put(r.resourceId(), r.revisionId()));
        return entries;
    }

    /**
     * Whether the loaded entries hash to the stored manifest hash and every spec matches its hash.
     *
     * @return {@code true} if the generation was loaded completely and intact
     */
    public boolean isIntact() {
        return SnapshotManifest.hash(manifest()).equals(manifestHash)
                && resources.stream().allMatch(PublishedResource::specHashMatches);
    }
}
