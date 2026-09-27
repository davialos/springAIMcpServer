package com.springaimcpservercommon.security.internal;

import com.springaimcpservercommon.security.TestFixtures.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalHelpersTest {

    @Test
    void ttlCacheExpiresBoundsAndHonoursEarlierExpiry() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-28T10:00:00Z"));
        TtlCache<String, String> cache = new TtlCache<>(2, Duration.ofMinutes(5), clock);
        cache.put("a", "1");
        cache.put("b", "2", clock.instant().plusSeconds(10));
        assertThat(cache.get("a")).contains("1");
        cache.put("c", "3"); // evicts least recently used ("b")
        assertThat(cache.get("b")).isEmpty();
        assertThat(cache.size()).isEqualTo(2);

        cache.put("d", "4", clock.instant().plusSeconds(10));
        clock.advance(Duration.ofSeconds(11));
        assertThat(cache.get("d")).isEmpty();
        clock.advance(Duration.ofMinutes(5));
        assertThat(cache.get("a")).isEmpty();

        cache.put("e", "5", clock.instant().plus(Duration.ofHours(1))); // clamped to max TTL
        clock.advance(Duration.ofMinutes(6));
        assertThat(cache.get("e")).isEmpty();
    }

    @Test
    void globPatternsAreLiteralExceptStarAndQuestionMark() {
        assertThat(GlobPattern.compile("sg-sales-*", false).matches("sg-sales-analysts")).isTrue();
        assertThat(GlobPattern.compile("sg-sales-*", false).matches("sg-sales")).isFalse();
        assertThat(GlobPattern.compile("team-?", false).matches("team-a")).isTrue();
        assertThat(GlobPattern.compile("a.b(c)+", false).matches("a.b(c)+")).isTrue();
        assertThat(GlobPattern.compile("a.b", false).matches("axb")).isFalse();
        assertThat(GlobPattern.compile("CN=X*", true).matches("cn=xyz")).isTrue();
        assertThat(GlobPattern.compile("ROLE_A", false).matches("role_a")).isFalse();
        assertThatThrownBy(() -> GlobPattern.compile("", false)).isInstanceOf(IllegalArgumentException.class);
    }
}
