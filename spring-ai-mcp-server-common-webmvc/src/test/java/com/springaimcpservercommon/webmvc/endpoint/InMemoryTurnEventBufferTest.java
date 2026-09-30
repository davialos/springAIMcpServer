package com.springaimcpservercommon.webmvc.endpoint;

import com.springaimcpservercommon.ai.runtime.StreamEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryTurnEventBufferTest {

    private final UUID turn = UUID.randomUUID();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    private static StreamEvent event(int n) {
        return new StreamEvent.TextDelta(n, "chunk-" + n);
    }

    @Test
    void ownerReadsEventsAfterASequence() {
        try (InMemoryTurnEventBuffer buffer = new InMemoryTurnEventBuffer()) {
            for (int i = 0; i < 4; i++) {
                buffer.append(turn, alice, i, event(i));
            }

            List<TurnEventBuffer.BufferedEvent> all = buffer.since(turn, alice, -1);
            List<TurnEventBuffer.BufferedEvent> tail = buffer.since(turn, alice, 1);

            assertThat(all).extracting(TurnEventBuffer.BufferedEvent::seq).containsExactly(0, 1, 2, 3);
            assertThat(tail).extracting(TurnEventBuffer.BufferedEvent::seq).containsExactly(2, 3);
        }
    }

    @Test
    void anotherPrincipalCannotTellAForeignTurnFromAnUnknownOne() {
        try (InMemoryTurnEventBuffer buffer = new InMemoryTurnEventBuffer()) {
            buffer.append(turn, alice, 0, event(0));

            assertThat(buffer.since(turn, bob, -1)).isNull();
            assertThat(buffer.since(UUID.randomUUID(), alice, -1)).isNull();
        }
    }

    @Test
    void aTurnCannotBeAppendedToByAnotherPrincipal() {
        try (InMemoryTurnEventBuffer buffer = new InMemoryTurnEventBuffer()) {
            buffer.append(turn, alice, 0, event(0));

            assertThatThrownBy(() -> buffer.append(turn, bob, 1, event(1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(buffer.since(turn, alice, -1)).hasSize(1);
        }
    }

    @Test
    void capacityDropsTheOldestEvents() {
        try (InMemoryTurnEventBuffer buffer = new InMemoryTurnEventBuffer(3, Duration.ofMinutes(5))) {
            for (int i = 0; i < 5; i++) {
                buffer.append(turn, alice, i, event(i));
            }

            assertThat(buffer.since(turn, alice, -1)).extracting(TurnEventBuffer.BufferedEvent::seq)
                    .containsExactly(2, 3, 4);
        }
    }
}
