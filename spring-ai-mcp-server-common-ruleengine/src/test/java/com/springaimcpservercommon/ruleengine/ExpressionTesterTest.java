package com.springaimcpservercommon.ruleengine;

import com.springaimcpservercommon.ruleengine.admin.ExpressionTester;
import com.springaimcpservercommon.ruleengine.cel.ParameterLibrary;
import com.springaimcpservercommon.ruleengine.model.Outcome;
import com.springaimcpservercommon.ruleengine.support.SampleTenant;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExpressionTesterTest {

    private final ExpressionTester tester = new ExpressionTester(() -> new ParameterLibrary(SampleTenant.parameters()));

    @Test
    void validateReportsIssuesAndTheParametersRead() {
        var ok = tester.validate("customer.age >= 18 && loan.amount < 10.0");
        assertThat(ok.valid()).isTrue();
        assertThat(ok.parameters()).containsExactlyInAnyOrder("customer.age", "loan.amount");

        var bad = tester.validate("customer.height > 3");
        assertThat(bad.valid()).isFalse();
        assertThat(bad.errors().getFirst()).contains("customer.height");
        assertThat(tester.validate("customer.age").valid()).as("must be boolean").isFalse();
    }

    @Test
    void testRunsTheExpressionOnSampleValuesWithoutEchoingThem() {
        assertThat(tester.test("customer.age >= 18", Map.of("customer", Map.of("age", 20))).outcome()).isEqualTo(Outcome.TRUE);
        assertThat(tester.test("customer.age >= 18", Map.of("customer.age", 5)).outcome()).isEqualTo(Outcome.FALSE);

        var missing = tester.test("customer.age >= 18", Map.of());
        assertThat(missing.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(missing.errorCode()).isEqualTo("MISSING_PARAMETER");

        var wrong = tester.test("customer.age >= 18", Map.of("customer", Map.of("age", "SECRET")));
        assertThat(wrong.errorCode()).isEqualTo("INVALID_PARAMETER");
        assertThat(wrong.detail()).doesNotContain("SECRET");

        assertThat(tester.test("customer.nope", Map.of()).errorCode()).isEqualTo("COMPILE_ERROR");
    }
}
