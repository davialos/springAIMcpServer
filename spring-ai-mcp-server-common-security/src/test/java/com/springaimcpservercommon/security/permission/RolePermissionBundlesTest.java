package com.springaimcpservercommon.security.permission;

import com.springaimcpservercommon.core.principal.FrameworkRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RolePermissionBundlesTest {

    private static final Pattern STORED = Pattern.compile("^[a-z][a-z-]*:[a-z][a-z-]*$");

    @Test
    void everyPermissionValueFitsTheStoreConstraint() {
        for (Permission permission : Permission.values()) {
            assertThat(permission.value()).matches(STORED);
            assertThat(Permission.of(permission.value())).isSameAs(permission);
        }
        assertThat(Permission.fromValue("ENDPOINT:Author")).contains(Permission.ENDPOINT_AUTHOR);
        assertThat(Permission.fromValue("nope:nope")).isEmpty();
    }

    @Test
    void defaultsReproduceTheSec01Matrix() {
        RolePermissionBundles b = RolePermissionBundles.defaults();
        assertThat(b.permissionsOf(FrameworkRole.SECURITY_ADMIN))
                .containsExactlyInAnyOrder(Permission.ROLEMAPPING_MANAGE, Permission.SERVICEACCOUNT_MANAGE, Permission.AUDIT_READ);
        assertThat(b.permissionsOf(FrameworkRole.AUDITOR)).containsExactly(Permission.AUDIT_READ);
        assertThat(b.permissionsOf(FrameworkRole.OPERATOR)).containsExactly(Permission.OPS_KILLSWITCH);
        assertThat(b.permissionsOf(FrameworkRole.CONSUMER)).isEmpty();
        assertThat(b.grants(FrameworkRole.APPROVER, Permission.DATA_WRITE_APPROVE)).isTrue();
        assertThat(b.grants(FrameworkRole.APPROVER, Permission.ENDPOINT_AUTHOR)).isFalse();
        assertThat(b.grants(FrameworkRole.AUTHOR, Permission.REVIEW_APPROVE)).isFalse();
        assertThat(b.grants(FrameworkRole.PLATFORM_ADMIN, Permission.CATALOG_DECLASSIFY)).isTrue();
        assertThat(b.grants(FrameworkRole.PLATFORM_ADMIN, Permission.ROLEMAPPING_MANAGE)).isFalse();
        assertThat(b.grants(FrameworkRole.WORKSPACE_OWNER, Permission.CATALOG_DECLASSIFY)).isFalse();
        assertThat(b.grants(FrameworkRole.WORKSPACE_OWNER, Permission.TOOL_PUBLISH)).isTrue();
    }

    @Test
    void grantOnlyPermissionsAreNeverInBundles() {
        RolePermissionBundles.defaults().asMap().values().forEach(set ->
                assertThat(set).allMatch(p -> p.kind() == Permission.Kind.ROLE));
        assertThatThrownBy(() -> RolePermissionBundles.defaults()
                .withBundle(FrameworkRole.CONSUMER, List.of(Permission.AGENT_INVOKE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RolePermissionBundles.defaults()
                .withBundle(FrameworkRole.CONSUMER, List.of(Permission.MCP_READ)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bundlesAreOverridableAsData() {
        RolePermissionBundles b = RolePermissionBundles.defaults()
                .withBundle(FrameworkRole.OPERATOR, List.of(Permission.OPS_KILLSWITCH, Permission.AUDIT_READ));
        assertThat(b.grants(FrameworkRole.OPERATOR, Permission.AUDIT_READ)).isTrue();
        assertThat(RolePermissionBundles.defaults().grants(FrameworkRole.OPERATOR, Permission.AUDIT_READ)).isFalse();
    }

    @Test
    void mcpScopesMapToApiKeyPermissions() {
        assertThat(McpScope.fromValue("dai.mcp.read")).contains(McpScope.READ);
        assertThat(McpScope.PROPOSE.apiKeyPermission()).isEqualTo(Permission.MCP_PROPOSE);
        assertThat(McpScope.fromValue("DAI.MCP.READ")).isEmpty();
    }
}
