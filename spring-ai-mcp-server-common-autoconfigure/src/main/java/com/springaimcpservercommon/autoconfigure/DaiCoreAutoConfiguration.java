package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.catalog.MetadataRegistry;
import com.springaimcpservercommon.core.catalog.SwappableMetadataRegistry;
import com.springaimcpservercommon.core.environment.DefaultEnvironmentSafetyPolicy;
import com.springaimcpservercommon.core.environment.EnvironmentSafetyPolicy;
import com.springaimcpservercommon.core.environment.EnvironmentSignals;
import org.jspecify.annotations.NullMarked;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Auto-configuration for the core domain model: the metadata registry and environment safety policy.
 *
 * <p>This configuration is always active when the starter is on the classpath. Every bean is
 * {@link ConditionalOnMissingBean} — host applications and test configurations may replace any of them.
 */
@AutoConfiguration
@EnableConfigurationProperties(DaiProperties.class)
@NullMarked
public class DaiCoreAutoConfiguration {

    /**
     * The in-process metadata registry. Starts with an empty, fail-closed catalog (generation 0).
     * The scan-and-merge process publishes the first real generation after startup.
     *
     * @return the registry
     */
    @Bean
    @ConditionalOnMissingBean(MetadataRegistry.class)
    public SwappableMetadataRegistry metadataRegistry() {
        EffectiveCatalog empty = new EffectiveCatalog(
                0L, "sha256:empty", "sha256:empty",
                Map.of(), Map.of(), List.of(), List.of());
        return new SwappableMetadataRegistry(empty);
    }

    /**
     * The environment safety policy. Reads active profiles from Spring's {@link Environment} and the
     * {@code dynamic.ai.agent.environment.tier} property to determine the effective tier.
     *
     * @param props       framework properties
     * @param environment Spring environment
     * @return the policy
     */
    @Bean
    @ConditionalOnMissingBean(EnvironmentSafetyPolicy.class)
    public EnvironmentSafetyPolicy environmentSafetyPolicy(DaiProperties props, Environment environment) {
        DaiProperties.Environment envProps = props.environment();
        List<String> prodPatterns = envProps.prodProfilePatterns() != null && !envProps.prodProfilePatterns().isEmpty()
                ? envProps.prodProfilePatterns()
                : DefaultEnvironmentSafetyPolicy.DEFAULT_PROD_PROFILE_PATTERNS;
        return new DefaultEnvironmentSafetyPolicy(prodPatterns, null, java.time.Clock.systemUTC());
    }

    /**
     * Reads current Spring environment signals for the safety policy.
     *
     * @param props       framework properties
     * @param environment Spring environment
     * @return the signals
     */
    @Bean
    @ConditionalOnMissingBean(EnvironmentSignals.class)
    public EnvironmentSignals environmentSignals(DaiProperties props, Environment environment) {
        DaiProperties.Environment envProps = props.environment();
        List<String> profiles = Arrays.asList(environment.getActiveProfiles());
        String appName = envProps.applicationName() != null
                ? envProps.applicationName()
                : environment.getProperty("spring.application.name");
        return new EnvironmentSignals(
                envProps.tier().isBlank() || "UNKNOWN".equalsIgnoreCase(envProps.tier()) ? null : envProps.tier(),
                profiles,
                envProps.id(),
                appName);
    }
}
