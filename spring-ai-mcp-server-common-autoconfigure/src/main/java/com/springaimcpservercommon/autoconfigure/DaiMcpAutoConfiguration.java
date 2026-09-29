package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.tool.ToolBridge;
import com.springaimcpservercommon.core.catalog.MetadataRegistry;
import com.springaimcpservercommon.mcp.server.McpEndpointController;
import com.springaimcpservercommon.mcp.server.McpProtocolHandler;
import com.springaimcpservercommon.mcp.server.McpRequestRecorder;
import com.springaimcpservercommon.mcp.server.McpTransportMode;
import com.springaimcpservercommon.persistence.telemetry.TelemetryStore;
import com.springaimcpservercommon.security.authz.AuthorizationEngine;
import com.springaimcpservercommon.security.mcp.McpClientApproval;
import com.springaimcpservercommon.security.mcp.McpScopeEvaluator;
import com.springaimcpservercommon.security.port.McpClientRegistryPort;
import com.springaimcpservercommon.security.principal.IdentityClaimSettings;
import com.springaimcpservercommon.security.principal.IdentityExtraction;
import com.springaimcpservercommon.webmvc.endpoint.GenericDynamicHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import com.springaimcpservercommon.mcp.server.DefaultMcpToolsProvider;
import com.springaimcpservercommon.mcp.server.McpOriginValidator;
import com.springaimcpservercommon.mcp.server.McpToolBindingSource;
import com.springaimcpservercommon.mcp.server.McpToolsProvider;
import com.springaimcpservercommon.mcp.server.ProtectedResourceMetadata;
import org.jspecify.annotations.NullMarked;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Auto-configuration for the MCP server integration.
 *
 * <p>Activated when {@link McpOriginValidator} is on the classpath (mcp module present).
 * Registers the origin validator, protected-resource metadata, and the default tool-list provider.
 *
 * <p>With {@code dynamic.ai.agent.mcp.enabled=true} it also registers the MCP endpoint itself
 * ({@link McpEndpointController}, {@code POST /dynamic-ai/mcp}, stateless Streamable HTTP; ADR-0016). Authentication
 * is the host's Spring Security filter chain; this configuration adds no security filters.
 */
@AutoConfiguration(after = {DaiCoreAutoConfiguration.class, DaiPersistenceAutoConfiguration.class,
                             DaiSecurityAutoConfiguration.class, DaiAiAutoConfiguration.class,
                             DaiWebMvcAutoConfiguration.class})
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "dynamic.ai.agent", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnClass(McpOriginValidator.class)
@NullMarked
public class DaiMcpAutoConfiguration {

    /**
     * MCP DNS-rebinding protection. Allows only the origins listed under
     * {@code dynamic.ai.agent.mcp.allowed-origins}. An absent Origin (server-to-server) is always allowed.
     *
     * @param props framework properties
     * @return the validator
     */
    @Bean
    @ConditionalOnMissingBean
    public McpOriginValidator mcpOriginValidator(DaiProperties props) {
        List<String> origins = props.mcp().allowedOrigins();
        return new McpOriginValidator(origins != null ? origins : List.of());
    }

    /**
     * RFC 9728 protected-resource metadata advertised at {@code /dynamic-ai/mcp/.well-known/oauth-protected-resource}.
     *
     * <p>The {@code resourceUri} defaults to the value of {@code dynamic.ai.agent.mcp.resource-uri};
     * when not configured, uses a placeholder that the host must override with a concrete bean.
     *
     * @param props framework properties
     * @return the metadata
     */
    @Bean
    @ConditionalOnMissingBean
    public ProtectedResourceMetadata protectedResourceMetadata(DaiProperties props) {
        DaiProperties.Mcp mcp = props.mcp();
        String resourceUri = mcp.resourceUri() != null ? mcp.resourceUri() : "/dynamic-ai/mcp";
        List<String> authServers = mcp.authorizationServers() != null ? mcp.authorizationServers() : List.of();
        return new ProtectedResourceMetadata(resourceUri, authServers,
                ProtectedResourceMetadata.DEFAULT_SCOPES, List.of("header"), null);
    }

    /**
     * The published tool bindings marked {@code mcpExposed}, per workspace.
     *
     * @param cache published bindings
     * @return the source
     */
    @Bean
    @ConditionalOnMissingBean(McpToolBindingSource.class)
    @ConditionalOnBean(ToolBindingSnapshotCache.class)
    public McpToolBindingSource mcpToolBindingSource(ToolBindingSnapshotCache cache) {
        return cache::mcpExposed;
    }

    /**
     * Default MCP tools provider. Registered when the {@link ToolBridge} and a
     * {@link McpToolBindingSource} bean are both available.
     *
     * @param bindingSource loads the workspace's MCP-exposed bindings
     * @param toolBridge    resolves and secures tool callbacks
     * @return the provider
     */
    @Bean
    @ConditionalOnMissingBean(McpToolsProvider.class)
    @ConditionalOnBean({ToolBridge.class, McpToolBindingSource.class})
    public DefaultMcpToolsProvider mcpToolsProvider(McpToolBindingSource bindingSource, ToolBridge toolBridge) {
        return new DefaultMcpToolsProvider(bindingSource, toolBridge);
    }

    /**
     * Store-backed recording of MCP requests ({@code dai_mcp_request}). Declared before the no-op default so it wins.
     *
     * @param store telemetry store
     * @return the recorder
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(McpRequestRecorder.class)
    @ConditionalOnBean(TelemetryStore.class)
    StoreMcpRequestRecorder storeMcpRequestRecorder(TelemetryStore store) {
        return new StoreMcpRequestRecorder(store);
    }

    /**
     * No-op request recorder, used without the store.
     *
     * @return the recorder
     */
    @Bean
    @ConditionalOnMissingBean(McpRequestRecorder.class)
    public McpRequestRecorder mcpRequestRecorder() {
        return McpRequestRecorder.NOOP;
    }

    /**
     * Scope and grant evaluation for MCP tools (LLD-07 §5.4).
     *
     * @param engine authorization engine
     * @return the evaluator
     */
    @Bean
    @ConditionalOnMissingBean(McpScopeEvaluator.class)
    @ConditionalOnBean(AuthorizationEngine.class)
    public McpScopeEvaluator mcpScopeEvaluator(AuthorizationEngine engine) {
        return new McpScopeEvaluator(engine, null);
    }

    /**
     * Approved-MCP-client check (LLD-07 §5.3), on unless {@code dynamic.ai.agent.mcp.require-approved-client=false}.
     *
     * @param registry approved clients per workspace
     * @param settings identity claim settings
     * @return the approval
     */
    @Bean
    @ConditionalOnMissingBean(McpClientApproval.class)
    @ConditionalOnBean({McpClientRegistryPort.class, IdentityClaimSettings.class})
    @ConditionalOnProperty(prefix = "dynamic.ai.agent.mcp", name = "require-approved-client", havingValue = "true",
            matchIfMissing = true)
    public McpClientApproval mcpClientApproval(McpClientRegistryPort registry, IdentityClaimSettings settings) {
        return new McpClientApproval(registry, IdentityExtraction.defaults(), settings);
    }

    /**
     * The MCP endpoint (F-55). Off by default; needs the store, the security layer and a principal resolver.
     *
     * @param provider       tools per caller
     * @param evaluator      scope evaluator
     * @param registry       live catalog
     * @param recorder       request recording
     * @param resolver       resolves the authenticated caller
     * @param origins        DNS-rebinding protection
     * @param approvals      approved-client check, absent when disabled
     * @param metadata       RFC 9728 metadata
     * @param props          framework properties
     * @return the controller
     */
    @Bean
    @ConditionalOnMissingBean(McpEndpointController.class)
    @ConditionalOnProperty(prefix = "dynamic.ai.agent.mcp", name = "enabled", havingValue = "true")
    @ConditionalOnBean({McpToolsProvider.class, McpScopeEvaluator.class,
                        GenericDynamicHandler.DaiPrincipalResolver.class, MetadataRegistry.class})
    public McpEndpointController mcpEndpointController(McpToolsProvider provider, McpScopeEvaluator evaluator,
                                                       MetadataRegistry registry, McpRequestRecorder recorder,
                                                       GenericDynamicHandler.DaiPrincipalResolver resolver,
                                                       McpOriginValidator origins,
                                                       ObjectProvider<McpClientApproval> approvals,
                                                       ProtectedResourceMetadata metadata, DaiProperties props) {
        DaiProperties.Mcp mcp = props.mcp();
        if (mcp.transport() == McpTransportMode.STATEFUL) {
            LOG.warn("dynamic.ai.agent.mcp.transport=STATEFUL is not implemented; serving STATELESS");
        }
        McpClientApproval approval = approvals.getIfAvailable();
        if (approval == null) {
            LOG.warn("MCP client approval is off: any authenticated caller with the right scopes may use the endpoint");
        }
        String version = McpProtocolHandler.class.getPackage().getImplementationVersion();
        McpProtocolHandler handler = new McpProtocolHandler(
                (caller, scope) -> provider.toolsForRequest(caller.principal(), caller.authentication(),
                        registry.current(), caller.workspaceId(), evaluator, scope),
                recorder, java.time.Clock.systemUTC(), "spring-ai-mcp-server-common",
                version == null ? "unknown" : version);
        return new McpEndpointController(handler, resolver::resolve, origins, approval, metadata,
                new McpEndpointController.Settings(mcp.workspaceId(), mcp.maxRequestBytes(), mcp.resourceUri()));
    }

    private static final Logger LOG = LoggerFactory.getLogger(DaiMcpAutoConfiguration.class);
}
