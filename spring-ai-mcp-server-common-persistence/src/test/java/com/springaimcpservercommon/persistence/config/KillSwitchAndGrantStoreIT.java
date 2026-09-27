package com.springaimcpservercommon.persistence.config;

import com.springaimcpservercommon.persistence.config.support.DaiTestDatabase;
import com.springaimcpservercommon.persistence.config.support.MutableClock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KillSwitchAndGrantStoreIT {

    private static DaiTestDatabase db;
    private static MutableClock clock;
    private static UUID operator;
    private static UUID workspace;

    @BeforeAll
    static void setUp() {
        db = DaiTestDatabase.create();
        clock = new MutableClock(Instant.parse("2026-09-28T08:00:00Z"));
        operator = db.user("operator");
        workspace = db.workspace("switches");
    }

    @AfterAll
    static void tearDown() {
        db.close();
    }

    @Test
    void activeSetExcludesClearedAndExpiredSwitches() {
        KillSwitchStore switches = new KillSwitchStore(db.store(), clock);
        KillSwitchView global = switches.set(new KillSwitchTarget.Global(), "provider outage", operator, null);
        KillSwitchView ws = switches.set(new KillSwitchTarget.Workspace(workspace), "runaway cost", operator,
                clock.instant().plus(Duration.ofMinutes(10)));
        KillSwitchView tool = switches.set(new KillSwitchTarget.Tool(workspace, "delete_order"), "bug", operator, null);

        assertThat(switches.listActive()).extracting(KillSwitchView::id)
                .containsExactlyInAnyOrder(global.id(), ws.id(), tool.id());
        assertThat(switches.listActive()).filteredOn(k -> k.id().equals(tool.id())).singleElement()
                .satisfies(k -> assertThat(k.target()).isEqualTo(new KillSwitchTarget.Tool(workspace, "delete_order")));

        clock.advance(Duration.ofMinutes(11));
        assertThat(switches.listActive()).extracting(KillSwitchView::id).containsExactlyInAnyOrder(global.id(), tool.id());

        assertThat(switches.clear(global.id(), operator)).isTrue();
        assertThat(switches.clear(global.id(), operator)).isFalse();
        assertThat(switches.listActive()).extracting(KillSwitchView::id).containsExactly(tool.id());
        assertThat(switches.history(Instant.parse("2026-09-28T00:00:00Z"))).hasSizeGreaterThanOrEqualTo(3);

        assertThatThrownBy(() -> switches.set(new KillSwitchTarget.Global(), " ", operator, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void grantsForPrincipalsExcludeExpiredAndIncludeWorkspaceWideAndPatternGrants() {
        GrantStore grants = new GrantStore(db.store(), clock);
        ConfigStore config = new ConfigStore(db.store(), clock, Duration.ofSeconds(10));
        UUID user = db.user("grantee");
        UUID group = db.user("grantee-group");
        UUID resource = config.createResource(workspace, ResourceKind.AGENT, "helpdesk",
                DraftContent.of("{\"a\":1}"), operator).resourceId();
        UUID otherResource = config.createResource(workspace, ResourceKind.AGENT, "billing",
                DraftContent.of("{\"a\":2}"), operator).resourceId();

        GrantView onResource = grants.create(workspace, user, "agent:invoke", new GrantTarget.OnResource(resource),
                "{\"region\":[\"EU\"]}", null, operator);
        GrantView workspaceWide = grants.create(workspace, group, "query:invoke", new GrantTarget.WorkspaceWide(),
                null, null, operator);
        GrantView pattern = grants.create(workspace, user, "agent:invoke", new GrantTarget.OnPattern("agent/help*"),
                null, null, operator);
        GrantView other = grants.create(workspace, user, "agent:invoke", new GrantTarget.OnResource(otherResource),
                null, clock.instant().plus(Duration.ofMinutes(5)), operator);

        assertThat(onResource.conditionsJson()).isEqualTo("{\"region\":[\"EU\"]}");
        assertThat(grants.grantsFor(List.of(user, group), workspace, resource)).extracting(GrantView::id)
                .containsExactlyInAnyOrder(onResource.id(), workspaceWide.id(), pattern.id());
        assertThat(grants.grantsFor(List.of(user), null, null)).extracting(GrantView::id)
                .contains(onResource.id(), pattern.id(), other.id());

        clock.advance(Duration.ofMinutes(6));
        assertThat(grants.grantsFor(List.of(user), null, null)).extracting(GrantView::id).doesNotContain(other.id());
        assertThat(grants.grantsFor(List.of(), workspace, null)).isEmpty();

        assertThatThrownBy(() -> grants.create(workspace, group, "query:invoke", new GrantTarget.WorkspaceWide(),
                null, null, operator))
                .satisfies(e -> assertThat(DaiTestDatabase.messages(e)).contains("uq_grant"));

        assertThat(grants.revoke(pattern.id())).isTrue();
        assertThat(grants.grantsInWorkspace(workspace)).extracting(GrantView::id).doesNotContain(pattern.id());
    }
}
