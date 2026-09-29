package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.tool.ToolBridge;
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
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Auto-configuration for the MCP server integration.
 *
 * <p>Activated when {@link McpOriginValidator} is on the classpath (mcp module present).
 * Registers the origin validator, protected-resource metadata, and the default tool-list provider.
 *
 * <p>The MCP server bean itself (from the Spring AI MCP SDK) must be configured by the host
 * using the {@link McpToolsProvider} registered here and the transport mode from {@link DaiProperties}.
 */
@AutoConfiguration(after = {DaiCoreAutoConfiguration.class, DaiAiAutoConfiguration.class})
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
}
