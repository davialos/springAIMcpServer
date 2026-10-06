package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ruleengine.RuleEngine;
import com.springaimcpservercommon.ruleengine.channel.ApiEnvironmentPolicy;
import com.springaimcpservercommon.ruleengine.channel.ChannelDelivery;
import com.springaimcpservercommon.ruleengine.channel.ChannelDispatcher;
import com.springaimcpservercommon.ruleengine.channel.OutboxChannelDelivery;
import com.springaimcpservercommon.ruleengine.admin.RuleConfigAdmin;
import com.springaimcpservercommon.ruleengine.admin.RuleLifecycle;
import com.springaimcpservercommon.ruleengine.channel.ApiEnvironmentClassifier;
import com.springaimcpservercommon.ruleengine.channel.EmailSender;
import com.springaimcpservercommon.ruleengine.model.ApiEnvironment;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;

class DaiRuleEngineWiringTest {

    @Configuration
    @EnableConfigurationProperties(DaiProperties.class)
    static class Host {
        @Bean
        DataSource dataSource() {
            // never used: the wiring is checked without touching a database
            return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "stub DataSource";
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        @Bean
        EmailSender emailSender() {
            return message -> { };
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DaiRuleEngineAutoConfiguration.class))
            .withUserConfiguration(Host.class);

    @Test
    void theEngineIsOffByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(RuleEngine.class));
    }

    @Test
    void whenEnabledTheEngineAndItsPartsAreWired() {
        runner.withPropertyValues("dynamic.ai.agent.rule-engine.enabled=true",
                        "dynamic.ai.agent.environment.tier=dev")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(RuleEngine.class);
                    assertThat(ctx.getBean(ApiEnvironmentPolicy.class).runningEnvironment())
                            .isEqualTo(ApiEnvironment.DEV);
                });
    }

    @Test
    void aMissingOrUnknownTierIsProduction() {
        runner.withPropertyValues("dynamic.ai.agent.rule-engine.enabled=true")
                .run(ctx -> assertThat(ctx.getBean(ApiEnvironmentPolicy.class).runningEnvironment())
                        .isEqualTo(ApiEnvironment.PROD));
    }

    @Test
    void anInvalidPollIntervalFailsStartup() {
        runner.withPropertyValues("dynamic.ai.agent.rule-engine.enabled=true",
                        "dynamic.ai.agent.rule-engine.poll-interval=100ms")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void deliveryIsDirectByDefaultAndTheOutboxOnlyWhenAskedFor() {
        RuleOutboxLifecycle[] lifecycle = new RuleOutboxLifecycle[1];
        runner.withPropertyValues("dynamic.ai.agent.rule-engine.enabled=true").run(ctx -> {
            assertThat(ctx.getBean(ChannelDelivery.class)).isInstanceOf(ChannelDispatcher.class);
            assertThat(ctx).doesNotHaveBean(RuleOutboxLifecycle.class);
        });
        runner.withPropertyValues("dynamic.ai.agent.rule-engine.enabled=true", "dynamic.ai.agent.rule-engine.delivery=OUTBOX")
                .run(ctx -> {
                    assertThat(ctx.getBean(ChannelDelivery.class)).isInstanceOf(OutboxChannelDelivery.class);
                    assertThat(ctx).hasSingleBean(RuleOutboxLifecycle.class);
                    assertThat(ctx.getBean(RuleOutboxLifecycle.class).isRunning()).as("started with the context").isTrue();
                    lifecycle[0] = ctx.getBean(RuleOutboxLifecycle.class);
                });
        assertThat(lifecycle[0].isRunning()).as("stopped when the context closes").isFalse();
    }

    @Test
    void theAuthoringServicesAreWiredAndReviewIsRequiredByDefault() {
        runner.withPropertyValues("dynamic.ai.agent.rule-engine.enabled=true").run(ctx -> {
            assertThat(ctx).hasSingleBean(RuleLifecycle.class).hasSingleBean(RuleConfigAdmin.class)
                    .hasSingleBean(ApiEnvironmentClassifier.class);
            assertThat(ctx.getBean(DaiRuleEngineProperties.class).requireReview()).isTrue();
        });
    }

    @Test
    void apiHostPatternsComeFromTheProperties() {
        runner.withPropertyValues("dynamic.ai.agent.rule-engine.enabled=true",
                        "dynamic.ai.agent.rule-engine.api-hosts.prod[0]=api.acme.com",
                        "dynamic.ai.agent.rule-engine.api-hosts.qa[0]=*.qa.acme.com")
                .run(ctx -> {
                    ApiEnvironmentClassifier c = ctx.getBean(ApiEnvironmentClassifier.class);
                    assertThat(c.classify("https://api.acme.com/x")).isEqualTo(ApiEnvironment.PROD);
                    assertThat(c.classify("https://pay.qa.acme.com/x")).isEqualTo(ApiEnvironment.QA);
                    assertThat(c.classify("http://localhost:8080/x")).as("default dev hosts").isEqualTo(ApiEnvironment.DEV);
                    assertThat(c.classify("https://elsewhere.example/x")).isEqualTo(ApiEnvironment.EXTERNAL);
                });
    }

    @Test
    void invalidOutboxSettingsFailStartup() {
        for (String bad : new String[]{"outbox.max-attempts=0", "outbox.batch-size=1000", "outbox.poll-interval=100ms",
                "outbox.lease=1s", "outbox.base-delay=0s"}) {
            runner.withPropertyValues("dynamic.ai.agent.rule-engine.enabled=true", "dynamic.ai.agent.rule-engine." + bad)
                    .run(ctx -> assertThat(ctx).as(bad).hasFailed());
        }
    }
}
