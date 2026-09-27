package com.springaimcpservercommon.persistence.identity;

import com.springaimcpservercommon.core.principal.FrameworkRole;
import com.springaimcpservercommon.persistence.config.support.DaiTestDatabase;
import com.springaimcpservercommon.persistence.config.support.MutableClock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityStoresIT {

    private static DaiTestDatabase db;
    private static MutableClock clock;
    private static UUID admin;

    @BeforeAll
    static void setUp() {
        db = DaiTestDatabase.create();
        clock = new MutableClock(Instant.parse("2026-09-28T08:00:00Z"));
        admin = db.user("admin");
    }

    @AfterAll
    static void tearDown() {
        db.close();
    }

    // ------------------------------------------------------------------ API keys

    @Test
    void apiKeyLookupByPrefixReturnsScopesAndNetworksUntilRevokedOrExpired() {
        ApiKeyStore keys = new ApiKeyStore(db.store(), clock, Duration.ofMinutes(5));
        UUID workspace = db.workspace("keys");
        ServiceAccountView account = keys.createServiceAccount(workspace, "etl-job", "nightly export", admin);
        Instant expiry = clock.instant().plus(Duration.ofDays(90));

        ApiKeyView created = keys.createKey(new NewApiKey(account.id(), "dai_test_AbCdEf123456", "hmac:deadbeef",
                ApiKeyHashAlgorithm.HMAC_SHA256, expiry, Set.of("agent:invoke", "query:read"),
                List.of("10.0.0.0/8", "2001:db8::/32"), admin));
        assertThat(created.allowedNetworks()).containsExactlyInAnyOrder("10.0.0.0/8", "2001:db8::/32");

        ApiKeyView found = keys.findActiveByPrefix("dai_test_AbCdEf123456").orElseThrow();
        assertThat(found.id()).isEqualTo(created.id());
        assertThat(found.principalId()).isEqualTo(account.principalId());
        assertThat(found.workspaceId()).isEqualTo(workspace);
        assertThat(found.keyHash()).isEqualTo("hmac:deadbeef");
        assertThat(found.scopes()).containsExactlyInAnyOrder("agent:invoke", "query:read");
        assertThat(found.allowedNetworks()).hasSize(2);
        assertThat(keys.findActiveByPrefix("dai_test_unknown0001")).isEmpty();
        assertThat(keys.findActiveByPrefix("not a prefix")).isEmpty();

        assertThat(keys.touchLastUsed(created.id())).isTrue();
        assertThat(keys.touchLastUsed(created.id())).isFalse();
        clock.advance(Duration.ofMinutes(6));
        assertThat(keys.touchLastUsed(created.id())).isTrue();

        keys.setServiceAccountEnabled(account.id(), false, admin);
        assertThat(keys.findActiveByPrefix("dai_test_AbCdEf123456")).isEmpty();
        keys.setServiceAccountEnabled(account.id(), true, admin);
        assertThat(keys.findActiveByPrefix("dai_test_AbCdEf123456")).isPresent();

        assertThat(keys.revoke(created.id(), admin)).isTrue();
        assertThat(keys.revoke(created.id(), admin)).isFalse();
        assertThat(keys.findActiveByPrefix("dai_test_AbCdEf123456")).isEmpty();

        ApiKeyView shortLived = keys.createKey(new NewApiKey(account.id(), "dai_test_Expiring0001", "hmac:beef",
                ApiKeyHashAlgorithm.HMAC_SHA256, clock.instant().plus(Duration.ofHours(1)), Set.of(), List.of(), admin));
        assertThat(keys.findActiveByPrefix(shortLived.keyPrefix())).isPresent();
        clock.advance(Duration.ofHours(2));
        assertThat(keys.findActiveByPrefix(shortLived.keyPrefix())).isEmpty();
        assertThat(keys.keysOf(account.id())).hasSize(2);
    }

    @Test
    void invalidNetworksAreRejectedByPostgres() {
        ApiKeyStore keys = new ApiKeyStore(db.store(), clock, Duration.ofMinutes(5));
        ServiceAccountView account = keys.createServiceAccount(db.workspace("net"), "bad-net", null, admin);

        assertThatThrownBy(() -> keys.createKey(new NewApiKey(account.id(), "dai_test_HostBits0001", "h",
                ApiKeyHashAlgorithm.HMAC_SHA256, clock.instant().plus(Duration.ofDays(1)), Set.of(),
                List.of("10.0.0.1/8"), admin)))
                .satisfies(e -> assertThat(DaiTestDatabase.messages(e)).contains("cidr"));
        assertThat(keys.findActiveByPrefix("dai_test_HostBits0001")).isEmpty();
    }

    // ------------------------------------------------------------------ workspaces

    @Test
    void rolesForUnionsNonExpiredMembershipsOfActiveWorkspaces() {
        WorkspaceStore workspaces = new WorkspaceStore(db.store(), clock);
        UUID ws1 = db.workspace("sales");
        UUID ws2 = db.workspace("ops");
        UUID user = db.user("carol");
        UUID group = db.user("group-sales");

        workspaces.addMember(ws1, user, FrameworkRole.AUTHOR, admin, null);
        workspaces.addMember(ws1, group, FrameworkRole.APPROVER, admin, null);
        workspaces.addMember(ws2, user, FrameworkRole.OPERATOR, admin, clock.instant().plus(Duration.ofMinutes(30)));
        assertThatThrownBy(() -> workspaces.addMember(ws1, user, FrameworkRole.PLATFORM_ADMIN, admin, null))
                .isInstanceOf(IllegalArgumentException.class);

        Map<UUID, Set<FrameworkRole>> roles = workspaces.rolesFor(List.of(user, group));
        assertThat(roles.get(ws1)).containsExactlyInAnyOrder(FrameworkRole.AUTHOR, FrameworkRole.APPROVER);
        assertThat(roles.get(ws2)).containsExactly(FrameworkRole.OPERATOR);

        clock.advance(Duration.ofHours(1));
        assertThat(workspaces.rolesFor(List.of(user))).containsOnlyKeys(ws1);

        workspaces.archive(ws1, admin);
        assertThat(workspaces.rolesFor(List.of(user, group))).isEmpty();
        assertThat(workspaces.members(ws1)).hasSize(2);
        assertThat(workspaces.removeMember(ws1, group, FrameworkRole.APPROVER)).isTrue();
    }

    @Test
    void workspaceUpdateUsesOptimisticLocking() {
        WorkspaceStore workspaces = new WorkspaceStore(db.store(), clock);
        WorkspaceView ws = workspaces.findBySlug(workspaces.find(db.workspace("lock")).orElseThrow().slug()).orElseThrow();
        WorkspaceView updated = workspaces.update(ws.id(), ws.rowVersion(), "Renamed", null, ws.clearance(), admin);
        assertThat(updated.name()).isEqualTo("Renamed");
        assertThatThrownBy(() -> workspaces.update(ws.id(), ws.rowVersion(), "Again", null, ws.clearance(), admin))
                .isInstanceOf(jakarta.persistence.OptimisticLockException.class);
    }

    // ------------------------------------------------------------------ role mappings

    @Test
    void roleMappingsAreUniqueWithNullsNotDistinct() {
        RoleMappingStore mappings = new RoleMappingStore(db.store(), clock);
        RoleMappingRule globalAdmin = new RoleMappingRule(RoleMappingSource.AUTHORITY, null, null,
                "ROLE_PLATFORM_ADMIN", FrameworkRole.PLATFORM_ADMIN, null, 10);
        mappings.create(globalAdmin, "bootstrap", admin);

        // issuer, claim_name and workspace_id are all NULL: a plain UNIQUE would accept this duplicate
        assertThatThrownBy(() -> mappings.create(globalAdmin, "duplicate", admin))
                .satisfies(e -> assertThat(DaiTestDatabase.messages(e)).contains("uq_role_mapping"));

        RoleMappingView claim = mappings.create(new RoleMappingRule(RoleMappingSource.OIDC_CLAIM,
                "https://idp.example.test", "groups", "sg-auditors", FrameworkRole.AUDITOR, null, 50), null, admin);
        assertThat(mappings.enabledMappings()).extracting(RoleMappingView::id).contains(claim.id());
        mappings.setEnabled(claim.id(), false, admin);
        assertThat(mappings.enabledMappings()).extracting(RoleMappingView::id).doesNotContain(claim.id());
        assertThat(mappings.delete(claim.id())).isTrue();
    }

    // ------------------------------------------------------------------ MCP clients

    @Test
    void consentCanBeRegrantedAndIsRevokedWithTheClient() {
        McpClientStore clients = new McpClientStore(db.store(), clock);
        UUID workspace = db.workspace("mcp");
        UUID user = db.user("dave");
        McpClientView client = clients.register(workspace, "https://idp.example.test", "claude-desktop",
                "Claude Desktop", McpRegistrationType.PRE_REGISTERED, admin);

        assertThatThrownBy(() -> clients.grantConsent(client.id(), user, EnumSet.of(McpConsentScope.READ)))
                .isInstanceOf(IllegalStateException.class);
        clients.approve(client.id(), admin);
        assertThat(clients.findApproved("https://idp.example.test", "claude-desktop"))
                .extracting(McpClientView::id).containsExactly(client.id());

        McpConsentView first = clients.grantConsent(client.id(), user, EnumSet.of(McpConsentScope.READ));
        McpConsentView second = clients.grantConsent(client.id(), user,
                EnumSet.of(McpConsentScope.READ, McpConsentScope.PROPOSE));
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(clients.activeConsent(client.id(), user).orElseThrow().scopes())
                .containsExactlyInAnyOrder(McpConsentScope.READ, McpConsentScope.PROPOSE);

        assertThat(clients.revoke(client.id())).isTrue();
        assertThat(clients.activeConsent(client.id(), user)).isEmpty();
        Integer active = db.jdbc().queryForObject("SELECT count(*) FROM " + db.table("dai_mcp_client_consent")
                + " WHERE mcp_client_id = ? AND revoked_at IS NULL", Integer.class, client.id());
        assertThat(active).isZero();
    }
}
