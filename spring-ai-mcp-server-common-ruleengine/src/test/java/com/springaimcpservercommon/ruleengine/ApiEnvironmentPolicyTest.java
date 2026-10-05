package com.springaimcpservercommon.ruleengine;

import com.springaimcpservercommon.core.environment.EnvironmentTier;
import com.springaimcpservercommon.ruleengine.channel.ApiCheck.Verdict;
import com.springaimcpservercommon.ruleengine.channel.ApiEnvironmentClassifier;
import com.springaimcpservercommon.ruleengine.channel.ApiEnvironmentPolicy;
import com.springaimcpservercommon.ruleengine.model.ApiEnvironment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiEnvironmentPolicyTest {

    @Test
    void eachTierMayOnlyCallItsOwnEnvironment() {
        assertThat(new ApiEnvironmentPolicy(EnvironmentTier.DEV).checkForSave(ApiEnvironment.DEV, false).allowed()).isTrue();
        assertThat(new ApiEnvironmentPolicy(EnvironmentTier.DEV).checkForSave(ApiEnvironment.QA, false).verdict())
                .isEqualTo(Verdict.REJECTED_ENVIRONMENT_MISMATCH);
        assertThat(new ApiEnvironmentPolicy(EnvironmentTier.TEST).checkForSave(ApiEnvironment.QA, false).allowed()).isTrue();
        assertThat(new ApiEnvironmentPolicy(EnvironmentTier.STAGE).checkForSave(ApiEnvironment.QA, false).allowed()).isTrue();
        assertThat(new ApiEnvironmentPolicy(EnvironmentTier.PROD).checkForSave(ApiEnvironment.PROD, false).allowed()).isTrue();
        assertThat(new ApiEnvironmentPolicy(EnvironmentTier.PROD).checkForSave(ApiEnvironment.DEV, false).allowed()).isFalse();
    }

    @Test
    void anUnknownTierIsTreatedAsProduction() {
        ApiEnvironmentPolicy policy = new ApiEnvironmentPolicy(EnvironmentTier.UNKNOWN);
        assertThat(policy.runningEnvironment()).isEqualTo(ApiEnvironment.PROD);
        assertThat(policy.checkForSave(ApiEnvironment.QA, false).allowed()).isFalse();
    }

    @Test
    void anExternalApiNeedsAConfirmationPopupBeforeItIsAccepted() {
        ApiEnvironmentPolicy policy = new ApiEnvironmentPolicy(EnvironmentTier.PROD);

        var first = policy.checkForSave(ApiEnvironment.EXTERNAL, false);
        assertThat(first.verdict()).isEqualTo(Verdict.CONFIRMATION_REQUIRED);
        assertThat(first.message()).contains("external API").contains("confirm");

        assertThat(policy.checkForSave(ApiEnvironment.EXTERNAL, true).allowed()).isTrue();
    }

    @Test
    void theClassifierUsesHostPatternsAndTreatsAnythingUnclearAsExternal() {
        var classifier = new ApiEnvironmentClassifier(Map.of(
                ApiEnvironment.DEV, List.of("localhost", "*.dev.acme.com", "shared.acme.com"),
                ApiEnvironment.QA, List.of("*.qa.acme.com", "shared.acme.com"),
                ApiEnvironment.PROD, List.of("api.acme.com")));

        assertThat(classifier.classify("http://localhost:8080/x")).isEqualTo(ApiEnvironment.DEV);
        assertThat(classifier.classify("https://pay.qa.acme.com/hook")).isEqualTo(ApiEnvironment.QA);
        assertThat(classifier.classify("https://api.acme.com/hook")).isEqualTo(ApiEnvironment.PROD);
        assertThat(classifier.classify("https://pay.dev.acme.com/hook")).isEqualTo(ApiEnvironment.DEV);
        // matches a DEV and a QA pattern: ambiguous, so it cannot be validated
        assertThat(classifier.classify("https://shared.acme.com/hook")).isEqualTo(ApiEnvironment.EXTERNAL);
        assertThat(classifier.classify("https://partner.example.org/hook")).isEqualTo(ApiEnvironment.EXTERNAL);
        assertThat(classifier.classify("not a url")).isEqualTo(ApiEnvironment.EXTERNAL);
    }
}
