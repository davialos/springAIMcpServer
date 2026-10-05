package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.environment.EnvironmentTier;
import com.springaimcpservercommon.persistence.unit.DaiStore;
import com.springaimcpservercommon.ruleengine.EvaluationRecorder;
import com.springaimcpservercommon.ruleengine.RuleEngine;
import com.springaimcpservercommon.ruleengine.cache.RuleCatalogCache;
import com.springaimcpservercommon.ruleengine.channel.ApiCaller;
import com.springaimcpservercommon.ruleengine.channel.ApiEnvironmentPolicy;
import com.springaimcpservercommon.ruleengine.channel.ChannelDispatcher;
import com.springaimcpservercommon.ruleengine.channel.EmailSender;
import com.springaimcpservercommon.ruleengine.channel.HttpApiCaller;
import com.springaimcpservercommon.ruleengine.channel.PushSender;
import com.springaimcpservercommon.ruleengine.store.JdbcEvaluationRecorder;
import com.springaimcpservercommon.ruleengine.store.JdbcRuleStore;
import com.springaimcpservercommon.ruleengine.store.RuleStore;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * The CEL rule engine (ADR-0025, LLD-18). Off unless {@code dynamic.ai.agent.rule-engine.enabled=true} and the
 * {@code spring-ai-mcp-server-common-ruleengine} artifact is on the class path. It reads the {@code dai_re_*} tables
 * (migration V11, created by the persistence unit) through the host's {@link DataSource}, caches the parameter
 * library, messages and each tenant's rules in memory, and refreshes them from the database change markers, so any
 * number of replicas stay in step with no extra infrastructure.
 *
 * <p>The host supplies what only it can: an {@link EmailSender} and a {@link PushSender} bean (without them those
 * channels are skipped). Every bean here is {@code @ConditionalOnMissingBean}, so any part can be replaced.
 */
@NullMarked
@AutoConfiguration(after = DaiPersistenceAutoConfiguration.class,
        afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnProperty(prefix = "dynamic.ai.agent", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "dynamic.ai.agent.rule-engine", name = "enabled", havingValue = "true")
@ConditionalOnClass(RuleEngine.class)
@EnableConfigurationProperties(DaiRuleEngineProperties.class)
public class DaiRuleEngineAutoConfiguration {

    private static final String DEFAULT_SCHEMA = "dynamic_ai";

    /**
     * Reads rules from the {@code dynamic_ai} schema.
     *
     * @param dataSource host data source
     * @param store      the persistence unit, when present (its schema is used)
     * @return the store
     */
    @Bean
    @ConditionalOnMissingBean(RuleStore.class)
    @ConditionalOnBean(DataSource.class)
    public RuleStore ruleStore(DataSource dataSource, ObjectProvider<DaiStore> store) {
        DaiStore unit = store.getIfAvailable();
        return new JdbcRuleStore(dataSource, unit != null ? unit.schema() : DEFAULT_SCHEMA);
    }

    /**
     * The in-memory snapshots of parameters, messages and tenant rules.
     *
     * @param store the rule store
     * @param props rule-engine settings
     * @return the cache
     */
    @Bean
    @ConditionalOnMissingBean(RuleCatalogCache.class)
    @ConditionalOnBean(RuleStore.class)
    public RuleCatalogCache ruleCatalogCache(RuleStore store, DaiRuleEngineProperties props) {
        return new RuleCatalogCache(store, Clock.systemUTC(), props.pollInterval(), props.maxTenants(),
                props.defaultLanguage());
    }

    /**
     * Writes the value-free evaluation log, or nothing when {@code record-evaluations=false}.
     *
     * @param dataSource host data source
     * @param store      the persistence unit, when present
     * @param props      rule-engine settings
     * @return the recorder
     */
    @Bean
    @ConditionalOnMissingBean(EvaluationRecorder.class)
    @ConditionalOnBean(DataSource.class)
    public EvaluationRecorder evaluationRecorder(DataSource dataSource, ObjectProvider<DaiStore> store,
                                                 DaiRuleEngineProperties props) {
        if (!props.recordEvaluations()) {
            return EvaluationRecorder.NONE;
        }
        DaiStore unit = store.getIfAvailable();
        return new JdbcEvaluationRecorder(dataSource, unit != null ? unit.schema() : DEFAULT_SCHEMA);
    }

    /**
     * Keeps each environment on its own APIs (an unknown tier is production, LLD-12).
     *
     * @param props framework properties (environment tier)
     * @return the guard
     */
    @Bean
    @ConditionalOnMissingBean(ApiEnvironmentPolicy.class)
    public ApiEnvironmentPolicy ruleEngineApiEnvironmentPolicy(DaiProperties props) {
        return new ApiEnvironmentPolicy(EnvironmentTier.parse(props.environment().tier()).orElse(EnvironmentTier.UNKNOWN));
    }

    /**
     * The HTTP caller of API channels.
     *
     * @return the default caller
     */
    @Bean
    @ConditionalOnMissingBean(ApiCaller.class)
    public ApiCaller ruleEngineApiCaller() {
        return new HttpApiCaller();
    }

    /**
     * Sends planned communications; e-mail and push use the host's beans when it has them.
     *
     * @param email  host mail port
     * @param push   host push port
     * @param api    HTTP port
     * @param policy API environment guard
     * @return the dispatcher
     */
    @Bean
    @ConditionalOnMissingBean(ChannelDispatcher.class)
    public ChannelDispatcher ruleEngineChannelDispatcher(ObjectProvider<EmailSender> email,
                                                         ObjectProvider<PushSender> push, ApiCaller api,
                                                         ApiEnvironmentPolicy policy) {
        return new ChannelDispatcher(email.getIfAvailable(), push.getIfAvailable(), api, policy);
    }

    /**
     * The rule engine facade applications call.
     *
     * @param cache      rule cache
     * @param dispatcher communications
     * @param recorder   evaluation log
     * @return the engine
     */
    @Bean
    @ConditionalOnMissingBean(RuleEngine.class)
    @ConditionalOnBean(RuleCatalogCache.class)
    public RuleEngine ruleEngine(RuleCatalogCache cache, ChannelDispatcher dispatcher, EvaluationRecorder recorder) {
        return new RuleEngine(cache, dispatcher, recorder);
    }
}
