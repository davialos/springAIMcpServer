package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.persistence.config.KillSwitchTarget;
import com.springaimcpservercommon.persistence.config.KillSwitchView;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KillSwitchViewAdapterTest {

    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    private final UUID ws = UUID.randomUUID();
    private final UUID resource = UUID.randomUUID();

    private static KillSwitchView view(KillSwitchTarget target, Instant expiresAt, Instant clearedAt) {
        return new KillSwitchView(UUID.randomUUID(), target, "maintenance", UUID.randomUUID(), NOW.minusSeconds(60),
                expiresAt, clearedAt == null ? null : UUID.randomUUID(), clearedAt);
    }

    private StoreSecurityPorts.KillSwitches adapter(KillSwitchView... views) {
        return new StoreSecurityPorts.KillSwitches(new TtlSnapshot<>("test", () -> List.of(views),
                Duration.ofSeconds(2), new TtlSnapshotTest.MutableClock()));
    }

    @Test
    void targetMatchingFollowsScope() {
        assertThat(StoreSecurityPorts.KillSwitches.matches(new KillSwitchTarget.Global(), null, null, null)).isTrue();
        assertThat(StoreSecurityPorts.KillSwitches.matches(new KillSwitchTarget.Workspace(ws), ws, null, null)).isTrue();
        assertThat(StoreSecurityPorts.KillSwitches.matches(new KillSwitchTarget.Workspace(ws), UUID.randomUUID(), null, null)).isFalse();
        assertThat(StoreSecurityPorts.KillSwitches.matches(new KillSwitchTarget.Workspace(ws), null, null, null)).isFalse();
        assertThat(StoreSecurityPorts.KillSwitches.matches(new KillSwitchTarget.Resource(null, resource), null, resource, null)).isTrue();
        assertThat(StoreSecurityPorts.KillSwitches.matches(new KillSwitchTarget.Resource(ws, resource), UUID.randomUUID(), resource, null)).isFalse();
        assertThat(StoreSecurityPorts.KillSwitches.matches(new KillSwitchTarget.Tool(null, "list_orders"), ws, null, "list_orders")).isTrue();
        assertThat(StoreSecurityPorts.KillSwitches.matches(new KillSwitchTarget.Tool(ws, "list_orders"), UUID.randomUUID(), null, "list_orders")).isFalse();
        assertThat(StoreSecurityPorts.KillSwitches.matches(new KillSwitchTarget.Tool(null, "list_orders"), ws, null, "other_tool")).isFalse();
    }

    @Test
    void anExpiredOrClearedSwitchDoesNotApply() {
        var adapter = adapter(view(new KillSwitchTarget.Global(), NOW.minusSeconds(1), null),
                view(new KillSwitchTarget.Global(), null, NOW.minusSeconds(5)));

        assertThat(adapter.findActive(ws, resource, null, NOW)).isEmpty();
    }

    @Test
    void theWidestMatchingScopeWins() {
        KillSwitchView resourceSwitch = view(new KillSwitchTarget.Resource(null, resource), null, null);
        KillSwitchView globalSwitch = view(new KillSwitchTarget.Global(), null, null);
        var adapter = adapter(resourceSwitch, globalSwitch);

        var active = adapter.findActive(ws, resource, null, NOW);

        assertThat(active).isPresent();
        assertThat(active.get().id()).isEqualTo(globalSwitch.id());
    }

    @Test
    void nothingMatchesWhenNoSwitchIsSet() {
        assertThat(adapter().findActive(ws, resource, "tool_x", NOW)).isEmpty();
    }
}
