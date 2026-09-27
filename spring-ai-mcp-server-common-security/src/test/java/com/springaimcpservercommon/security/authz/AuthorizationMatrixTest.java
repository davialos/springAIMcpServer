package com.springaimcpservercommon.security.authz;

import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.core.principal.FrameworkRole;
import com.springaimcpservercommon.security.TestFixtures;
import com.springaimcpservercommon.security.permission.Permission;
import com.springaimcpservercommon.security.permission.RolePermissionBundles;
import com.springaimcpservercommon.security.principal.GroupPrincipalIds;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEC-01 §12: authorization matrix generated from the role bundles — every role × every permission, in the role's own
 * workspace, in a foreign workspace and (for global roles) globally. No grants exist, so data-plane permissions must
 * always be denied (default deny).
 */
class AuthorizationMatrixTest {

    private static final UUID WS = UUID.fromString("0190a000-0000-7000-8000-00000000bbbb");
    private static final UUID OTHER_WS = UUID.fromString("0190a000-0000-7000-8000-00000000cccc");

    private static final AuthorizationEngine ENGINE;

    static {
        TestFixtures.MutableClock clock = new TestFixtures.MutableClock(Instant.parse("2026-09-28T10:00:00Z"));
        TestFixtures.InMemoryDirectory directory = new TestFixtures.InMemoryDirectory();
        ENGINE = AuthorizationEngine.builder(new TestFixtures.InMemoryGrants(), new TestFixtures.InMemoryKillSwitches(),
                        new TestFixtures.InMemoryResourceStatus(), new GroupPrincipalIds(directory, clock, Duration.ofMinutes(1), 10))
                .clock(clock).build();
    }

    static Stream<Arguments> matrix() {
        List<Arguments> rows = new ArrayList<>();
        for (FrameworkRole role : FrameworkRole.values()) {
            for (Permission permission : Permission.values()) {
                rows.add(Arguments.of(role, permission));
            }
        }
        return rows.stream();
    }

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("matrix")
    void decisionsFollowTheBundles(FrameworkRole role, Permission permission) {
        boolean expected = RolePermissionBundles.defaults().grants(role, permission);
        DaiPrincipal principal = role.globalOnly()
                ? TestFixtures.user(UUID.randomUUID(), Set.of(), Set.of(role), Map.of(), Map.of(), Classification.INTERNAL, Set.of())
                : TestFixtures.user(UUID.randomUUID(), Set.of(), Set.of(), Map.of(WS, Set.of(role)), Map.of(),
                Classification.INTERNAL, Set.of());

        boolean inWorkspace = ENGINE.decide(AuthorizationRequest.onWorkspace(principal, permission, WS)).granted();
        boolean foreign = ENGINE.decide(AuthorizationRequest.onWorkspace(principal, permission, OTHER_WS)).granted();
        boolean global = ENGINE.decide(AuthorizationRequest.global(principal, permission)).granted();

        assertThat(inWorkspace).as("own workspace").isEqualTo(expected);
        assertThat(foreign).as("foreign workspace").isEqualTo(expected && role.globalOnly());
        assertThat(global).as("global").isEqualTo(expected && role.globalOnly());
        if (permission.kind() != Permission.Kind.ROLE) {
            assertThat(inWorkspace).as("default deny for grant-only permissions").isFalse();
        }
    }
}
