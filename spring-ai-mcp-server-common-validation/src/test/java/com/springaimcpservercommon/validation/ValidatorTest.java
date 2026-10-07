package com.springaimcpservercommon.validation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidatorTest {

    record Order(String customer, int qty, String coupon) {
    }

    private static final ValidationContext SUBMIT =
            ValidationContext.of("SERVICE", "DRAFT", "POST /orders", "SUBMIT");

    private final ValidationRule<Order> customer = Rules.forType(Order.class).id("customer").order(10)
            .notBlank(Order::customer, "customer");
    private final ValidationRule<Order> qty = Rules.forType(Order.class).id("qty").order(20)
            .check(o -> o.qty() > 0, "qty", "qty must be positive");
    private final ValidationRule<Order> coupon = Rules.forType(Order.class).id("coupon").order(30)
            .scope(Scope.action("SUBMIT").andState("DRAFT|REJECTED"))
            .check(o -> o.coupon() != null, "coupon", "coupon required to submit");

    private Validator.Builder base() {
        return Validator.builder().rule(coupon).rule(qty).rule(customer);
    }

    @Test
    void collectsAllErrorsInOrder() {
        ValidationResult r = base().build().validate(new Order("", 0, null), SUBMIT);
        assertThat(r.isValid()).isFalse();
        assertThat(r.executed()).containsExactly("customer", "qty", "coupon");
        assertThat(r.errors()).extracting(Violation::field).containsExactly("customer", "qty", "coupon");
    }

    @Test
    void scopeLimitsRulesByStateAndAction() {
        Validator v = base().build();
        Order bad = new Order("c", 1, null);
        assertThat(v.validate(bad, SUBMIT).isValid()).isFalse();
        assertThat(v.validate(bad, SUBMIT.withState("APPROVED")).isValid()).isTrue();
        assertThat(v.validate(bad, ValidationContext.of("SERVICE", "DRAFT", "POST /orders", "SAVE")).isValid())
                .isTrue();
    }

    @Test
    void endpointWildcard() {
        ValidationRule<Order> r = Rules.forType(Order.class).id("e").scope(Scope.endpoint("* /orders/*"))
                .check(o -> false, null, "no");
        Validator v = Validator.builder().rule(r).build();
        assertThat(v.validate(new Order("c", 1, "x"), ValidationContext.of(null, null, "PUT /orders/7", null))
                .isValid()).isFalse();
        assertThat(v.validate(new Order("c", 1, "x"), ValidationContext.of(null, null, "PUT /users/7", null))
                .isValid()).isTrue();
    }

    @Test
    void overridesReorderPerContextMostSpecificWins() {
        Validator v = base()
                .override(OrderOverride.reorder("coupon", Scope.action("SUBMIT"), 5))
                .override(OrderOverride.reorder("coupon", Scope.action("SUBMIT").andState("DRAFT"), 50))
                .build();
        assertThat(v.explain(new Order("c", 1, "x"), SUBMIT)).containsExactly("customer", "qty", "coupon");
        assertThat(v.explain(new Order("c", 1, "x"), SUBMIT.withState("REJECTED")))
                .containsExactly("coupon", "customer", "qty");
    }

    @Test
    void overrideCanSkipARule() {
        Validator v = base().override(OrderOverride.skip("q*", Scope.endpoint("POST /orders"))).build();
        assertThat(v.explain(new Order("c", 0, "x"), SUBMIT)).containsExactly("customer", "coupon");
    }

    @Test
    void failFastAndStopOnFailure() {
        Order bad = new Order("", 0, null);
        assertThat(base().build().validate(bad, SUBMIT, ValidationOptions.FAIL_FAST).errors()).hasSize(1);

        ValidationRule<Order> gate = Rules.forType(Order.class).id("gate").order(0).stopOnFailure()
                .check(o -> o.customer() != null, "customer", "missing");
        ValidationResult r = base().rule(gate).build().validate(new Order(null, 0, null), SUBMIT);
        assertThat(r.executed()).containsExactly("gate");
    }

    @Test
    void warningsDoNotInvalidate() {
        ValidationRule<Order> w = Rules.forType(Order.class).id("w")
                .validate((o, c) -> List.of(Violation.warning("w", "qty", "big", "large order")));
        ValidationResult r = Validator.builder().rule(w).build().validateOrThrow(new Order("c", 1, "x"), SUBMIT);
        assertThat(r.isValid()).isTrue();
        assertThat(r.warnings()).hasSize(1);
    }

    @Test
    void throwingRuleFailsClosedAndOtherTypesAreSkipped() {
        ValidationRule<Order> boom = Rules.forType(Order.class).id("boom")
                .validate((o, c) -> {
                    throw new IllegalStateException("secret");
                });
        Validator v = Validator.builder().rule(boom).build();
        ValidationResult r = v.validate(new Order("c", 1, "x"), SUBMIT);
        assertThat(r.errors()).singleElement().satisfies(e -> {
            assertThat(e.code()).isEqualTo("rule_error");
            assertThat(e.message()).doesNotContain("secret");
        });
        assertThat(v.validate("not an order", SUBMIT).executed()).isEmpty();
        assertThatThrownBy(() -> v.validateOrThrow(new Order("c", 1, "x"), SUBMIT))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void duplicateIdsRejected() {
        assertThatThrownBy(() -> Validator.builder().rule(qty).rule(qty).build())
                .isInstanceOf(IllegalStateException.class);
    }
}
