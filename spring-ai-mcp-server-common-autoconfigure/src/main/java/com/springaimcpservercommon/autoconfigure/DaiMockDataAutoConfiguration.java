package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.annotations.CustomFakerProvider;
import com.springaimcpservercommon.core.environment.EnvironmentSafetyPolicy;
import com.springaimcpservercommon.core.environment.EnvironmentSignals;
import com.springaimcpservercommon.mockdata.MockDataEngine;
import com.springaimcpservercommon.mockdata.ai.AiMockGenerator;
import com.springaimcpservercommon.mockdata.ai.AiMockPool;
import com.springaimcpservercommon.mockdata.ai.FallbackMockDataEngine;
import com.springaimcpservercommon.mockdata.engine.DatafakerEngine;
import com.springaimcpservercommon.mockdata.simulation.DefaultSimulationGuard;
import com.springaimcpservercommon.mockdata.simulation.LoadSimulator;
import com.springaimcpservercommon.mockdata.simulation.SimulationGuard;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.util.ClassUtils;

import java.time.Clock;
import java.util.Locale;

/**
 * Mock data and load simulation (ADR-0031). With the {@code spring-ai-mcp-server-common-mockdata} artifact on the class
 * path the host gets a {@link MockDataEngine} (Datafaker, with the AI model as fallback for gaps when the host supplies an
 * {@link AiMockGenerator} bean or has a Spring AI {@code ChatClient.Builder} or {@code ChatModel}) and, with {@code dynamic.ai.agent.mockdata.simulator.enabled=true},
 * a {@link LoadSimulator} that refuses to run in production, against hosts outside its allow-list, or beyond its caps.
 * Every bean backs off to a bean of your own.
 */
@NullMarked
@AutoConfiguration(after = DaiCoreAutoConfiguration.class)
@ConditionalOnClass(MockDataEngine.class)
@ConditionalOnProperty(prefix = "dynamic.ai.agent.mockdata", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(DaiMockDataProperties.class)
public class DaiMockDataAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(DaiMockDataAutoConfiguration.class);

    /**
     * The mock-data engine: Datafaker, plus the AI pool when a model is available and the mode allows it.
     *
     * @param props   settings
     * @param ai      AI port (the Spring AI adapter or a bean of your own), if any
     * @param context the application context (looks up {@link CustomFakerProvider} beans)
     * @return the engine
     */
    @Bean
    @ConditionalOnMissingBean
    public MockDataEngine mockDataEngine(DaiMockDataProperties props, ObjectProvider<AiMockGenerator> ai, ApplicationContext context) {
        FallbackMockDataEngine.Mode mode = props.mode();
        DatafakerEngine.Options defaults = DatafakerEngine.Options.defaults();
        DatafakerEngine.Options options = new DatafakerEngine.Options(Locale.forLanguageTag(props.locale()), props.seed(), defaults.maxDepth(),
                defaults.maxCollectionSize(), mode == FallbackMockDataEngine.Mode.AI,
                type -> providerBean(context, type, defaults));
        AiMockGenerator generator = mode == FallbackMockDataEngine.Mode.FAKER ? null : ai.getIfAvailable();
        if (generator == null && mode != FallbackMockDataEngine.Mode.FAKER
                && ClassUtils.isPresent("org.springframework.ai.chat.client.ChatClient", context.getClassLoader())) {
            generator = SpringAiMockSupport.create(context, props.ai().timeout()); // null without a ChatClient.Builder or ChatModel
        }
        if (generator == null && mode != FallbackMockDataEngine.Mode.FAKER) {
            LOG.info("Mock data mode {} without an AI model: Datafaker only (add a Spring AI model to enable the fallback)", mode);
        }
        AiMockPool pool = generator == null ? null : new AiMockPool(generator,
                new AiMockPool.Settings(props.ai().batchSize(), props.ai().maxCalls(), props.ai().failureThreshold(), props.ai().cooldown()),
                Clock.systemUTC());
        return new FallbackMockDataEngine(new DatafakerEngine(options), pool, mode);
    }

    private static CustomFakerProvider providerBean(ApplicationContext context, Class<? extends CustomFakerProvider> type,
                                                    DatafakerEngine.Options defaults) {
        CustomFakerProvider bean = context.getBeanProvider(type).getIfUnique();
        return bean != null ? bean : defaults.providers().apply(type);
    }

    /**
     * The guard in front of every simulation. Production rules (PROD, or an undeclared tier) refuse everything; so does a
     * host without the environment beans (fail closed).
     *
     * @param props   settings
     * @param policy  environment safety policy
     * @param signals environment signals
     * @return the guard
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "dynamic.ai.agent.mockdata.simulator", name = "enabled", havingValue = "true")
    public SimulationGuard simulationGuard(DaiMockDataProperties props, ObjectProvider<EnvironmentSafetyPolicy> policy,
                                           ObjectProvider<EnvironmentSignals> signals) {
        DaiMockDataProperties.Simulator s = props.simulator();
        DefaultSimulationGuard.Limits limits = new DefaultSimulationGuard.Limits(s.allowedHosts(), s.allowLoopback(),
                s.maxVirtualUsers(), s.maxDuration(), s.maxRequestsPerSecond());
        return new DefaultSimulationGuard(limits, () -> {
            EnvironmentSafetyPolicy p = policy.getIfAvailable();
            EnvironmentSignals sig = signals.getIfAvailable();
            return p == null || sig == null || p.identify(sig).tier().productionRules();
        });
    }

    /**
     * The load simulator.
     *
     * @param guard decides whether a simulation may run
     * @return the simulator
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "dynamic.ai.agent.mockdata.simulator", name = "enabled", havingValue = "true")
    public LoadSimulator loadSimulator(SimulationGuard guard) {
        return new LoadSimulator(guard);
    }
}
