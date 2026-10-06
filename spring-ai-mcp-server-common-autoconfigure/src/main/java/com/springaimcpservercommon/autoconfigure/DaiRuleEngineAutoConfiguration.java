package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.environment.EnvironmentTier;
import com.springaimcpservercommon.persistence.unit.DaiStore;
import com.springaimcpservercommon.ruleengine.EvaluationRecorder;
import com.springaimcpservercommon.ruleengine.RuleEngine;
import com.springaimcpservercommon.ruleengine.cache.RuleCatalogCache;
import com.springaimcpservercommon.ruleengine.admin.ExpressionTester;
import com.springaimcpservercommon.ruleengine.admin.RuleConfigAdmin;
import com.springaimcpservercommon.ruleengine.admin.RuleLifecycle;
import com.springaimcpservercommon.ruleengine.channel.ApiCaller;
import com.springaimcpservercommon.ruleengine.channel.ApiEnvironmentClassifier;
import com.springaimcpservercommon.ruleengine.channel.ChannelDelivery;
import com.springaimcpservercommon.ruleengine.channel.OutboxChannelDelivery;
import com.springaimcpservercommon.ruleengine.channel.OutboxWorker;
import com.springaimcpservercommon.ruleengine.model.ApiEnvironment;
import com.springaimcpservercommon.ruleengine.store.OutboxStore;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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
@AutoConfiguration(after = {DaiPersistenceAutoConfiguration.class, DaiAdminAutoConfiguration.class},
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
     * The delivery outbox ({@code dai_re_dispatch}).
     *
     * @param dataSource host data source
     * @param store      the persistence unit, when present
     * @return the outbox
     */
    @Bean
    @ConditionalOnMissingBean(OutboxStore.class)
    @ConditionalOnBean(DataSource.class)
    public OutboxStore ruleEngineOutboxStore(DataSource dataSource, ObjectProvider<DaiStore> store) {
        return new OutboxStore(dataSource, schema(store));
    }

    /**
     * How communications leave the engine: sent directly on the caller's thread (default), or queued in the outbox
     * ({@code delivery=OUTBOX}). E-mail and push use the host's {@link EmailSender} / {@link PushSender} beans when it
     * has them. (The direct dispatcher is deliberately not a bean of its own: it is a {@link ChannelDelivery} too and
     * would hide this choice.)
     *
     * @param email  host mail port
     * @param push   host push port
     * @param api    HTTP port
     * @param policy API environment guard
     * @param outbox the outbox
     * @param props  rule-engine settings
     * @return the delivery
     */
    @Bean
    @ConditionalOnMissingBean(ChannelDelivery.class)
    @ConditionalOnBean(OutboxStore.class)
    public ChannelDelivery ruleEngineChannelDelivery(ObjectProvider<EmailSender> email, ObjectProvider<PushSender> push,
                                                     ApiCaller api, ApiEnvironmentPolicy policy, OutboxStore outbox,
                                                     DaiRuleEngineProperties props) {
        return props.delivery() == DaiRuleEngineProperties.Delivery.OUTBOX
                ? new OutboxChannelDelivery(outbox, policy, props.outbox().maxAttempts())
                : new ChannelDispatcher(email.getIfAvailable(), push.getIfAvailable(), api, policy);
    }

    /**
     * Drains the outbox with retries; only with {@code delivery=OUTBOX}.
     *
     * @param outbox the outbox
     * @param email  host mail port
     * @param push   host push port
     * @param api    HTTP port
     * @param policy API environment guard
     * @param props  rule-engine settings
     * @return the worker's lifecycle
     */
    @Bean
    @ConditionalOnProperty(prefix = "dynamic.ai.agent.rule-engine", name = "delivery", havingValue = "OUTBOX")
    @ConditionalOnBean(OutboxStore.class)
    RuleOutboxLifecycle ruleEngineOutboxWorker(OutboxStore outbox, ObjectProvider<EmailSender> email,
                                               ObjectProvider<PushSender> push, ApiCaller api, ApiEnvironmentPolicy policy,
                                               DaiRuleEngineProperties props) {
        DaiRuleEngineProperties.Outbox o = props.outbox();
        String workerId = "node-" + java.util.UUID.randomUUID();
        return new RuleOutboxLifecycle(new OutboxWorker(outbox, email.getIfAvailable(), push.getIfAvailable(), api, policy,
                Clock.systemUTC(), workerId, new OutboxWorker.Settings(o.batchSize(), o.lease(), o.baseDelay(), o.maxDelay(),
                o.deliveredRetention(), o.deadRetention())), o.pollInterval());
    }

    /**
     * The rule engine facade applications call.
     *
     * @param cache    rule cache
     * @param delivery communications
     * @param recorder evaluation log
     * @return the engine
     */
    @Bean
    @ConditionalOnMissingBean(RuleEngine.class)
    @ConditionalOnBean({RuleCatalogCache.class, ChannelDelivery.class})
    public RuleEngine ruleEngine(RuleCatalogCache cache, ChannelDelivery delivery, EvaluationRecorder recorder) {
        return new RuleEngine(cache, delivery, recorder);
    }

    // ---- authoring ------------------------------------------------------------------------------------------

    /**
     * Derives an API endpoint's environment from its host.
     *
     * @param props rule-engine settings (host patterns)
     * @return the classifier
     */
    @Bean
    @ConditionalOnMissingBean(ApiEnvironmentClassifier.class)
    public ApiEnvironmentClassifier ruleEngineApiClassifier(DaiRuleEngineProperties props) {
        Map<ApiEnvironment, List<String>> hosts = new EnumMap<>(ApiEnvironment.class);
        hosts.put(ApiEnvironment.DEV, props.apiHosts().dev());
        hosts.put(ApiEnvironment.QA, props.apiHosts().qa());
        hosts.put(ApiEnvironment.PROD, props.apiHosts().prod());
        return new ApiEnvironmentClassifier(hosts);
    }

    /**
     * Draft → review → publish of rules and groups.
     *
     * @param dataSource host data source
     * @param store      the persistence unit, when present
     * @param cache      supplies the current parameter library
     * @param props      rule-engine settings
     * @return the lifecycle service
     */
    @Bean
    @ConditionalOnMissingBean(RuleLifecycle.class)
    @ConditionalOnBean({DataSource.class, RuleCatalogCache.class})
    public RuleLifecycle ruleLifecycle(DataSource dataSource, ObjectProvider<DaiStore> store, RuleCatalogCache cache,
                                       DaiRuleEngineProperties props) {
        return new RuleLifecycle(dataSource, schema(store), cache::library, props.requireReview());
    }

    /**
     * Authoring of library, bundles, templates, endpoints, channels and triggers.
     *
     * @param dataSource host data source
     * @param store      the persistence unit, when present
     * @param cache      supplies the current parameter library
     * @param policy     API environment guard
     * @param classifier endpoint environment classifier
     * @return the service
     */
    @Bean
    @ConditionalOnMissingBean(RuleConfigAdmin.class)
    @ConditionalOnBean({DataSource.class, RuleCatalogCache.class})
    public RuleConfigAdmin ruleConfigAdmin(DataSource dataSource, ObjectProvider<DaiStore> store, RuleCatalogCache cache,
                                           ApiEnvironmentPolicy policy, ApiEnvironmentClassifier classifier) {
        return new RuleConfigAdmin(dataSource, schema(store), cache::library, policy, classifier);
    }

    /**
     * The expression editor's validator and test bench.
     *
     * @param cache supplies the current parameter library
     * @return the tester
     */
    @Bean
    @ConditionalOnMissingBean(ExpressionTester.class)
    @ConditionalOnBean(RuleCatalogCache.class)
    public ExpressionTester ruleExpressionTester(RuleCatalogCache cache) {
        return new ExpressionTester(cache::library);
    }

    private static String schema(ObjectProvider<DaiStore> store) {
        DaiStore unit = store.getIfAvailable();
        return unit != null ? unit.schema() : DEFAULT_SCHEMA;
    }
}
