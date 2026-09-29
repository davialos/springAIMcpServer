package com.springaimcpservercommon.webmvc.endpoint;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ClientRequestRegistryTest {

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-29T12:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final ClientRequestRegistry registry = new ClientRequestRegistry(Duration.ofMinutes(5), clock);
    private final UUID alice = UUID.randomUUID();
    private final UUID agent = UUID.randomUUID();

    @Test
    void firstRegistrationWinsAndTheRepeatSeesTheEarlierTurn() {
        UUID first = UUID.randomUUID();

        assertThat(registry.register(alice, agent, "req-1", first)).isNull();
        assertThat(registry.register(alice, agent, "req-1", UUID.randomUUID())).isEqualTo(first);
    }

    @Test
    void sameIdOfAnotherPrincipalOrAgentIsIndependent() {
        registry.register(alice, agent, "req-1", UUID.randomUUID());

        assertThat(registry.register(UUID.randomUUID(), agent, "req-1", UUID.randomUUID())).isNull();
        assertThat(registry.register(alice, UUID.randomUUID(), "req-1", UUID.randomUUID())).isNull();
    }

    @Test
    void releaseAllowsARetry() {
        registry.register(alice, agent, "req-1", UUID.randomUUID());
        registry.release(alice, agent, "req-1");

        assertThat(registry.register(alice, agent, "req-1", UUID.randomUUID())).isNull();
    }

    @Test
    void registrationsExpire() {
        registry.register(alice, agent, "req-1", UUID.randomUUID());
        clock.advance(Duration.ofMinutes(6));

        assertThat(registry.register(alice, agent, "req-1", UUID.randomUUID())).isNull();
    }
}
