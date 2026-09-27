package com.springaimcpservercommon.persistence.config;

import com.springaimcpservercommon.core.hash.Sha256;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotManifestTest {

    @Test
    void hashesSortedLinesIndependentOfMapOrder() {
        UUID r1 = UUID.fromString("00000000-0000-7000-8000-000000000001");
        UUID r2 = UUID.fromString("ffffffff-0000-7000-8000-000000000002");
        UUID v1 = UUID.fromString("11111111-0000-7000-8000-000000000001");
        UUID v2 = UUID.fromString("22222222-0000-7000-8000-000000000002");
        Map<UUID, UUID> forward = new LinkedHashMap<>();
        forward.put(r1, v1);
        forward.put(r2, v2);
        Map<UUID, UUID> reverse = new LinkedHashMap<>();
        reverse.put(r2, v2);
        reverse.put(r1, v1);

        String expected = Sha256.of(r1 + ":" + v1 + "\n" + r2 + ":" + v2);
        assertThat(SnapshotManifest.hash(forward)).isEqualTo(expected);
        assertThat(SnapshotManifest.hash(reverse)).isEqualTo(expected);
    }

    @Test
    void emptyLiveSetHashesTheEmptyString() {
        assertThat(SnapshotManifest.hash(Map.of())).isEqualTo(Sha256.of(""));
    }
}
