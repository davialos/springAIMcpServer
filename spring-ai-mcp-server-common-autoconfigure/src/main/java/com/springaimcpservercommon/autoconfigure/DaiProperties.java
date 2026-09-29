package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.mcp.server.McpTransportMode;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Top-level {@code @ConfigurationProperties} for the framework, bound under {@code dynamic.ai.agent}.
 *
 * <p>All sub-records use {@link DefaultValue} annotations so a host application does not need to
 * specify any property to get a working default configuration.
 *
 * @param environment     environment identification and safety policy
 * @param security        principal mapping and authorization settings
 * @param mcp             MCP server transport and origin configuration
 * @param query           dynamic query bulkhead settings
 * @param scan            {@code @Ai*} annotation scan settings
 * @param write           reviewed change-proposal settings (LLD-11 §12)
 */
@NullMarked
@ConfigurationProperties(prefix = "dynamic.ai.agent")
public record DaiProperties(
        @DefaultValue Environment environment,
        @DefaultValue Security security,
        @DefaultValue Mcp mcp,
        @DefaultValue Query query,
        @DefaultValue Scan scan,
        @DefaultValue Write write) {

    /**
     * Reviewed change-proposal settings (LLD-11 §12).
     *
     * @param retention how long proposals are kept after reaching a terminal state
     */
    public record Write(@DefaultValue("7d") Duration retention) {}

    /**
     * Environment identification settings.
     *
     * @param tier                 explicit environment tier (DEV, TEST, STAGE, PROD, UNKNOWN)
     * @param id                   optional stable environment identifier
     * @param applicationName      application name used when generating a default environment id
     * @param prodProfilePatterns  Spring profile patterns that imply production rules (glob, case-insensitive)
     */
    public record Environment(
            @DefaultValue("UNKNOWN") String tier,
            @Nullable String id,
            @Nullable String applicationName,
            @DefaultValue({"prod", "production", "live", "prd", "*-prod"}) List<String> prodProfilePatterns) {}

    /**
     * Principal-mapping and authorization settings.
     *
     * @param localIssuer         issuer string used for sessions not backed by an IdP
     * @param issuerOverride      when non-null, overrides the token issuer in all principal records
     * @param groupsClaim         JWT/SAML claim or LDAP attribute that carries the caller's groups
     * @param clearanceClaim      optional claim name for the caller's data classification clearance
     * @param defaultClearance    clearance applied when the claim is absent
     * @param cacheTtl            principal-mapping cache lifetime (≤ 5 minutes)
     * @param cacheMaxEntries     maximum number of cached principals per node
     * @param staticRoleMappings  bootstrap role-mapping rules not stored in the DB
     * @param attributeClaims     attribute name → claim name for ABAC attribute extraction
     */
    public record Security(
            @DefaultValue("local") String localIssuer,
            @Nullable String issuerOverride,
            @DefaultValue("groups") String groupsClaim,
            @Nullable String clearanceClaim,
            @DefaultValue("INTERNAL") Classification defaultClearance,
            @DefaultValue("5m") Duration cacheTtl,
            @DefaultValue("4096") int cacheMaxEntries,
            List<StaticRoleMapping> staticRoleMappings,
            Map<String, String> attributeClaims) {}

    /**
     * A single bootstrap role-mapping rule, equivalent to a {@code dai_role_mapping} row.
     *
     * @param source      where the matched value comes from (AUTHORITY, SCOPE, LDAP_GROUP, OIDC_CLAIM)
     * @param matchValue  glob pattern compared against values from the source
     * @param role        framework role to grant on a match
     * @param claimName   required when source is OIDC_CLAIM
     * @param workspaceId optional; when present the role is scoped to this workspace
     */
    public record StaticRoleMapping(
            String source,
            String matchValue,
            String role,
            @Nullable String claimName,
            @Nullable java.util.UUID workspaceId) {}

    /**
     * MCP server settings.
     *
     * @param transport          transport mode (STATELESS or STATEFUL); default STATELESS (ADR-0021)
     * @param allowedOrigins     Origin header values allowed for browser-based MCP clients (empty = any server-to-server)
     * @param resourceUri        RFC 9728 resource URI advertised in the protected-resource metadata endpoint
     * @param authorizationServers authorization server URIs included in the protected-resource metadata
     */
    public record Mcp(
            @DefaultValue("STATELESS") McpTransportMode transport,
            @DefaultValue List<String> allowedOrigins,
            @Nullable String resourceUri,
            @DefaultValue List<String> authorizationServers) {}

    /**
     * Dynamic query engine settings.
     *
     * @param maxConcurrency maximum concurrent JPA queries per node (bulkhead size)
     * @param timeout        maximum query execution time before the bulkhead times out
     */
    public record Query(
            @DefaultValue("20") int maxConcurrency,
            @DefaultValue("30s") Duration timeout) {}

    /**
     * {@code @Ai*} annotation scan settings ({@code dynamic.ai.agent.scan.*}).
     *
     * @param basePackages           packages to scan for {@code @AiExposedAction} and {@code @AiContext}; empty =
     *                               use the host's auto-configuration base packages (recommended)
     * @param strict                 exclude unbounded list-returning actions when {@code true}
     * @param outcomeActionThreshold emit a {@code CONSIDER_OUTCOME_ACTION} hint when an entity has more actions
     *                               than this threshold
     */
    public record Scan(
            @DefaultValue List<String> basePackages,
            @DefaultValue("false") boolean strict,
            @DefaultValue("8") int outcomeActionThreshold) {}
}
