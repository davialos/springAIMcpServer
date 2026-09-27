package com.springaimcpservercommon.core.id;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdsTest {

    @Test
    void generatesVersion7WithIetfVariant() {
        UUID id = Ids.newId();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void encodesTimestamp() {
        long millis = 1_790_000_000_000L;
        assertThat(Ids.epochMillis(Ids.newId(millis))).isEqualTo(millis);
    }

    @Test
    void idsAreOrderedByTime() {
        UUID earlier = Ids.newId(1_000L);
        UUID later = Ids.newId(2_000L);
        assertThat(earlier.toString().compareTo(later.toString())).isNegative();
    }

    @Test
    void rejectsOutOfRangeTimestamps() {
        assertThatThrownBy(() -> Ids.newId(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
