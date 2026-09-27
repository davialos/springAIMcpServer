package com.springaimcpservercommon.persistence.config;

import com.springaimcpservercommon.core.hash.Sha256;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Manifest hash of a snapshot generation ({@code dai_snapshot.manifest_hash}).
 *
 * <p>Definition: one line {@code <resourceId>:<revisionId>} per entry (UUIDs in their lowercase
 * {@link UUID#toString()} form), lines sorted by the resource id text (plain string order, not
 * {@link UUID#compareTo}, whose signed comparison differs from PostgreSQL's), joined with {@code \n} without a
 * trailing newline, hashed with {@link Sha256#of(String)}. An empty live set hashes the empty string. Nodes recompute
 * it after loading a generation to detect partial or corrupted loads (LLD-09 §6).
 */
public final class SnapshotManifest {

    private SnapshotManifest() {
    }

    /**
     * Computes the manifest hash.
     *
     * @param entries resource id → revision id of the live set
     * @return {@code sha256:<hex>}
     */
    public static String hash(Map<UUID, UUID> entries) {
        Objects.requireNonNull(entries, "entries");
        String text = entries.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue())
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining("\n"));
        return Sha256.of(text);
    }
}
