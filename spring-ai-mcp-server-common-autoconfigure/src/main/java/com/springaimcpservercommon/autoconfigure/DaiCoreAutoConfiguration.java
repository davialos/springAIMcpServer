package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.catalog.EntityCatalogSource;
import com.springaimcpservercommon.core.catalog.MetadataRegistry;
import com.springaimcpservercommon.core.catalog.ScanIssue;
import com.springaimcpservercommon.core.catalog.ScannedCatalog;
import com.springaimcpservercommon.core.catalog.SwappableMetadataRegistry;
import com.springaimcpservercommon.core.environment.DefaultEnvironmentSafetyPolicy;
import com.springaimcpservercommon.core.environment.EnvironmentSafetyPolicy;
import com.springaimcpservercommon.core.environment.EnvironmentSignals;
import com.springaimcpservercommon.core.lint.TextLint;
import com.springaimcpservercommon.core.policy.PolicyMerger;
import com.springaimcpservercommon.core.scan.ScanOptions;
import com.springaimcpservercommon.core.scan.SpringBeanOperationScanner;
import com.springaimcpservercommon.core.schema.JsonSchemaMapper;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.time.Clock;
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
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "dynamic.ai.agent", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({DaiProperties.class, DaiProductionOverrideProperties.class, DaiPiiProperties.class})
@NullMarked
public class DaiCoreAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DaiCoreAutoConfiguration.class);

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
    public EnvironmentSafetyPolicy environmentSafetyPolicy(DaiProperties props, Environment environment,
                                                           DaiProductionOverrideProperties overrideProps) {
        DaiProperties.Environment envProps = props.environment();
        List<String> prodPatterns = envProps.prodProfilePatterns() != null && !envProps.prodProfilePatterns().isEmpty()
                ? envProps.prodProfilePatterns()
                : DefaultEnvironmentSafetyPolicy.DEFAULT_PROD_PROFILE_PATTERNS;
        java.time.Clock clock = java.time.Clock.systemUTC();
        return new DefaultEnvironmentSafetyPolicy(prodPatterns, productionOverride(overrideProps, clock), clock);
    }

    /**
     * The break-glass override from configuration, validated; {@code null} when none is configured. A misconfigured
     * override stops the application at startup (a half-understood break-glass must not silently do nothing or
     * something else).
     */
    static com.springaimcpservercommon.core.environment.@org.jspecify.annotations.Nullable ProductionOverride productionOverride(
            DaiProductionOverrideProperties props, java.time.Clock clock) {
        if (props.capabilities().isEmpty() && props.expiresAt() == null && props.reason() == null) {
            return null;
        }
        if (props.capabilities().isEmpty() || props.expiresAt() == null || props.reason() == null) {
            throw new IllegalStateException("dynamic.ai.agent.environment.production-override needs capabilities, "
                    + "expires-at and reason together");
        }
        try {
            var override = com.springaimcpservercommon.core.environment.ProductionOverride.validated(
                    java.util.EnumSet.copyOf(props.capabilities()), props.expiresAt(), props.reason(), clock);
            log.warn("PRODUCTION OVERRIDE configured: {} until {} (reason: {}). Every use is audited.",
                    override.capabilities(), override.expiresAt(), override.reason());
            return override;
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("dynamic.ai.agent.environment.production-override is invalid: "
                    + e.getMessage(), e);
        }
    }

    /**
     * Finds personal data in text that is about to be stored (F-76): the built-in patterns plus the host's own.
     * Declare your own {@link com.springaimcpservercommon.core.lint.PiiDetector} to use a DLP service or a name
     * recogniser.
     *
     * @param props PII settings
     * @return the detector
     */
    @Bean
    @ConditionalOnMissingBean(com.springaimcpservercommon.core.lint.PiiDetector.class)
    public com.springaimcpservercommon.core.lint.PiiDetector piiDetector(DaiPiiProperties props) {
        java.util.Map<String, java.util.regex.Pattern> custom = new java.util.LinkedHashMap<>();
        props.customPatterns().forEach((label, regex) -> {
            try {
                custom.put(label, java.util.regex.Pattern.compile(regex));
            } catch (java.util.regex.PatternSyntaxException e) {
                throw new IllegalStateException("dynamic.ai.agent.conversations.pii.custom-patterns." + label
                        + " is not a valid regular expression");
            }
        });
        java.util.Set<com.springaimcpservercommon.core.lint.RegexPiiDetector.Type> types = props.types().isEmpty()
                ? com.springaimcpservercommon.core.lint.RegexPiiDetector.DEFAULT_TYPES
                : java.util.Set.copyOf(props.types());
        return new com.springaimcpservercommon.core.lint.RegexPiiDetector(types, custom);
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

    /**
     * Fires the {@link SpringBeanOperationScanner} after all singletons are instantiated, applies the
     * {@link PolicyMerger} with an empty policy layer list (generation 1), and swaps the
     * {@link SwappableMetadataRegistry} (LLD-03 §5).
     *
     * <p>Base packages default to {@link AutoConfigurationPackages#get(ListableBeanFactory)} when not
     * explicitly configured; if neither source yields packages the scan is skipped and the registry stays
     * at generation 0 (fail-closed).
     *
     * @param props          framework properties
     * @param metadataRegistry the registry to publish the first catalog generation into
     * @param beanFactory    the host application's bean factory
     * @param entitySources  zero or more entity catalog sources (one per host entity manager factory)
     * @return the startup singleton
     */
    @Bean
    @ConditionalOnMissingBean(name = "catalogBootstrap")
    public SmartInitializingSingleton catalogBootstrap(
            DaiProperties props,
            MetadataRegistry metadataRegistry,
            ListableBeanFactory beanFactory,
            List<EntityCatalogSource> entitySources) {
        return () -> {
            List<String> packages = props.scan().basePackages();
            if (packages.isEmpty()) {
                packages = AutoConfigurationPackages.has(beanFactory)
                        ? AutoConfigurationPackages.get(beanFactory) : List.of();
            }
            if (packages.isEmpty()) {
                log.warn("No base packages for AI catalog scan — MetadataRegistry stays at generation 0. "
                        + "Set dynamic.ai.agent.scan.base-packages or add @SpringBootApplication to the host.");
                return;
            }
            DaiProperties.Scan scanProps = props.scan();
            ScanOptions options = new ScanOptions(packages, List.of(ScanOptions.LIBRARY_PACKAGE),
                    scanProps.strict(), scanProps.outcomeActionThreshold(),
                    JsonSchemaMapper.Options.defaults(), TextLint.defaults(), null);
            SpringBeanOperationScanner scanner = new SpringBeanOperationScanner(options, Clock.systemUTC());
            ScannedCatalog scanned = scanner.scan(beanFactory, entitySources);
            long errors = scanned.issues().stream().filter(i -> i.severity() == ScanIssue.Severity.ERROR).count();
            if (errors > 0) {
                log.warn("AI catalog scan completed with {} error(s); affected elements excluded", errors);
            }
            PolicyMerger merger = new PolicyMerger(PolicyMerger.DEFAULT_GLOBAL_MAX_LIMIT, scanProps.strict());
            EffectiveCatalog effective = merger.merge(scanned, List.of(), 1L);
            if (metadataRegistry instanceof SwappableMetadataRegistry swappable) {
                swappable.publish(effective);
            } else {
                log.warn("Custom MetadataRegistry in use; the bootstrap catalog was not published into it");
                return;
            }
            log.info("AI catalog bootstrap published generation 1: {} entities, {} operations",
                    effective.entities().size(), effective.operations().size());
        };
    }
}
