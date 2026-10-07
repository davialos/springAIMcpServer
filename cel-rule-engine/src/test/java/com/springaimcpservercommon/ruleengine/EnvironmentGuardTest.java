package com.springaimcpservercommon.ruleengine;

import com.springaimcpservercommon.ruleengine.channel.EnvironmentGuard;
import com.springaimcpservercommon.ruleengine.channel.EnvironmentGuard.Kind;
import com.springaimcpservercommon.ruleengine.config.RuleEngineProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** An API may only be called from the environment it belongs to. */
class EnvironmentGuardTest {

    private static EnvironmentGuard guard(String environment) {
        return new EnvironmentGuard(new RuleEngineProperties(environment, Map.of("dev", List.of("localhost", "*.dev.acme.test"),
                "qa", List.of("*.qa.acme.test"), "prod", List.of("api.acme.com", "*.prod.acme.com")), null, null, false, null));
    }

    @Test
    void aDevSystemAcceptsDevApisAndRefusesQaAndProduction() {
        EnvironmentGuard dev = guard("DEV");
        assertThat(dev.classify("http://localhost:8080/hook").kind()).isEqualTo(Kind.SAME_ENVIRONMENT);
        assertThat(dev.classify("https://orders.dev.acme.test/events").kind()).isEqualTo(Kind.SAME_ENVIRONMENT);
        EnvironmentGuard.Classification qa = dev.classify("https://orders.qa.acme.test/events");
        assertThat(qa.kind()).isEqualTo(Kind.OTHER_ENVIRONMENT);
        assertThat(qa.environment()).isEqualTo("QA");
        assertThat(dev.classify("https://api.acme.com/x").environment()).isEqualTo("PROD");
    }

    @Test
    void productionAcceptsProductionApisOnly() {
        EnvironmentGuard prod = guard("PROD");
        assertThat(prod.classify("https://api.acme.com/x").kind()).isEqualTo(Kind.SAME_ENVIRONMENT);
        assertThat(prod.classify("http://localhost/x").kind()).isEqualTo(Kind.OTHER_ENVIRONMENT);
    }

    @Test
    void aHostInNoListIsExternalAndNeedsAConfirmation() {
        assertThat(guard("QA").classify("https://hooks.partner.example/notify").kind()).isEqualTo(Kind.EXTERNAL);
        assertThat(guard("QA").classify("https://x.dev.acme.test.evil.example/a").kind()).as("a look-alike is not a match")
                .isEqualTo(Kind.EXTERNAL);
    }

    @Test
    void onlyAbsoluteHttpUrlsAreAccepted() {
        assertThatThrownBy(() -> guard("DEV").classify("ftp://localhost/x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard("DEV").classify("/relative")).isInstanceOf(IllegalArgumentException.class);
    }
}
