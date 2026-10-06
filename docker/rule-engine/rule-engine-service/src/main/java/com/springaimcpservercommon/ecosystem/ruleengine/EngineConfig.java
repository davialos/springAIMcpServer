package com.springaimcpservercommon.ecosystem.ruleengine;

import com.springaimcpservercommon.core.environment.EnvironmentTier;
import com.springaimcpservercommon.ruleengine.EvaluationRecorder;
import com.springaimcpservercommon.ruleengine.RuleEngine;
import com.springaimcpservercommon.ruleengine.cache.RuleCatalogCache;
import com.springaimcpservercommon.ruleengine.channel.ApiCaller;
import com.springaimcpservercommon.ruleengine.channel.ApiEnvironmentPolicy;
import com.springaimcpservercommon.ruleengine.channel.ChannelDispatcher;
import com.springaimcpservercommon.ruleengine.channel.HttpApiCaller;
import com.springaimcpservercommon.ruleengine.store.JdbcEvaluationRecorder;
import com.springaimcpservercommon.ruleengine.store.JdbcRuleStore;
import com.springaimcpservercommon.ruleengine.store.RuleStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * Wires the library's rule engine over this service's data source, the way {@code DaiRuleEngineAutoConfiguration} does
 * for a host that uses the starter (the service needs no starter, no JPA, no library security). E-mail and push are
 * ports a host implements; this service has none, so those channels are reported {@code SKIPPED}, never sent.
 */
@Configuration
class EngineConfig {

    /** Schema of the library's tables. */
    static final String SCHEMA = "dynamic_ai";

    @Bean
    RuleStore ruleStore(DataSource dataSource) {
        return new JdbcRuleStore(dataSource, SCHEMA);
    }

    @Bean
    RuleCatalogCache ruleCatalogCache(RuleStore store, RuleEngineProperties props) {
        return new RuleCatalogCache(store, Clock.systemUTC(), props.pollInterval(), props.maxTenants(),
                props.defaultLanguage());
    }

    @Bean
    EvaluationRecorder evaluationRecorder(DataSource dataSource, RuleEngineProperties props) {
        return props.recordEvaluations() ? new JdbcEvaluationRecorder(dataSource, SCHEMA) : EvaluationRecorder.NONE;
    }

    @Bean
    ApiEnvironmentPolicy apiEnvironmentPolicy(RuleEngineProperties props) {
        return new ApiEnvironmentPolicy(EnvironmentTier.parse(props.environmentTier()).orElse(EnvironmentTier.UNKNOWN));
    }

    @Bean
    ApiCaller apiCaller() {
        return new HttpApiCaller();
    }

    @Bean
    ChannelDispatcher channelDispatcher(ApiCaller api, ApiEnvironmentPolicy policy) {
        return new ChannelDispatcher(null, null, api, policy);
    }

    @Bean
    RuleEngine ruleEngine(RuleCatalogCache cache, ChannelDispatcher dispatcher, EvaluationRecorder recorder) {
        return new RuleEngine(cache, dispatcher, recorder);
    }
}
