package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ruleengine.RuleEngine;
import com.springaimcpservercommon.ruleengine.channel.ApiEnvironmentPolicy;
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
}
