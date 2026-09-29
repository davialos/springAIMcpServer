package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.runtime.TurnRecorder.Finish;
import com.springaimcpservercommon.ai.runtime.TurnRecorder.Outcome;
import com.springaimcpservercommon.ai.runtime.TurnRecorder.TurnRecord;
import com.springaimcpservercommon.ai.agent.AgentDefinition;
import com.springaimcpservercommon.ai.agent.GuardrailSpec;
import com.springaimcpservercommon.ai.agent.LimitSpec;
import com.springaimcpservercommon.ai.agent.MemorySpec;
import com.springaimcpservercommon.ai.agent.ModelSelection;
import com.springaimcpservercommon.ai.agent.OutputSpec;
import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.invocation.Channel;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.core.principal.SubjectType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoreTurnRecorderTest {

    private static final DaiPrincipal PRINCIPAL = new DaiPrincipal(UUID.randomUUID(), SubjectType.USER, "local",
            "alice", "Alice", Set.of(), Set.of(), Map.of(), Map.of(), Classification.INTERNAL, null, Set.of());

    private static TurnRecord turn(Outcome outcome, long in, long out, UUID modelCallId) {
        Instant t = Instant.parse("2026-09-29T10:00:00Z");
        return new TurnRecord(UUID.randomUUID(), t, t.plusSeconds(1), null, agent(), PRINCIPAL, Channel.CHAT, null, null,
                outcome, outcome == Outcome.SUCCESS ? Finish.STOP : Finish.ERROR,
                outcome == Outcome.SUCCESS ? null : "boom", null, false, in, out, modelCallId);
    }

    private static AgentDefinition agent() {
        return new AgentDefinition(UUID.randomUUID(), 1, UUID.randomUUID(), "support-bot", "Support", "You help.",
                new ModelSelection("openai", "gpt-4o-mini", null, null, null), List.of(), MemorySpec.NONE,
                GuardrailSpec.OFF, LimitSpec.DEFAULT, OutputSpec.TEXT, Set.of(), "hash");
    }

    @Test
    void aModelCallIsRecordedOnlyWhenTheTurnIsKnownToHaveReachedTheModel() {
        UUID id = UUID.randomUUID();
        assertThat(StoreTurnRecorder.recordsModelCall(turn(Outcome.SUCCESS, 100, 10, id))).isTrue();
        assertThat(StoreTurnRecorder.recordsModelCall(turn(Outcome.SUCCESS, 0, 0, id))).isTrue();
        assertThat(StoreTurnRecorder.recordsModelCall(turn(Outcome.FAILED, 50, 0, id))).isTrue();
        assertThat(StoreTurnRecorder.recordsModelCall(turn(Outcome.FAILED, 0, 0, id))).isFalse();
        assertThat(StoreTurnRecorder.recordsModelCall(turn(Outcome.REJECTED, 0, 0, id))).isFalse();
        assertThat(StoreTurnRecorder.recordsModelCall(turn(Outcome.CANCELLED, 0, 0, id))).isFalse();
    }

    @Test
    void aTurnRecordAlwaysCarriesAModelCallId() {
        assertThatThrownBy(() -> turn(Outcome.SUCCESS, 1, 1, null)).isInstanceOf(NullPointerException.class);
    }
}
