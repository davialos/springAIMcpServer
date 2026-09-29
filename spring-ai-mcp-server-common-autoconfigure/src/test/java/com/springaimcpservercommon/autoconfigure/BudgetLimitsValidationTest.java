package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.persistence.usage.BudgetLimits;
import com.springaimcpservercommon.webmvc.problem.ProblemDetailFactory.FieldViolation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetLimitsValidationTest {

    private static BudgetAdminController.LimitsRequest req(Long tokens, Long cost, String currency, Integer soft,
                                                           Boolean hard) {
        return new BudgetAdminController.LimitsRequest(tokens, cost, currency, soft, hard);
    }

    @Test
    void tokenLimitWithDefaults() {
        List<FieldViolation> errors = new ArrayList<>();
        BudgetLimits limits = BudgetAdminController.limits(req(1_000L, null, null, null, null), errors);

        assertThat(errors).isEmpty();
        assertThat(limits).isNotNull();
        assertThat(limits.softLimitPct()).isEqualTo(BudgetLimits.DEFAULT_SOFT_LIMIT_PCT);
        assertThat(limits.hardLimit()).isTrue();
    }

    @Test
    void costLimitNeedsCurrencyAndNormalizesCase() {
        List<FieldViolation> errors = new ArrayList<>();
        BudgetLimits limits = BudgetAdminController.limits(req(null, 5_000_000L, "eur", 90, false), errors);

        assertThat(errors).isEmpty();
        assertThat(limits.currency()).isEqualTo("EUR");
        assertThat(limits.hardLimit()).isFalse();

        errors.clear();
        assertThat(BudgetAdminController.limits(req(null, 5_000_000L, null, null, null), errors)).isNull();
        assertThat(errors).extracting(FieldViolation::field).contains("currency");
    }

    @Test
    void rejectsMissingNonPositiveAndOutOfRangeValues() {
        List<FieldViolation> errors = new ArrayList<>();
        assertThat(BudgetAdminController.limits(null, errors)).isNull();
        assertThat(errors).extracting(FieldViolation::field).containsExactly("limits");

        errors.clear();
        assertThat(BudgetAdminController.limits(req(null, null, null, null, null), errors)).isNull();
        assertThat(errors).hasSize(1);

        errors.clear();
        assertThat(BudgetAdminController.limits(req(0L, null, null, 0, null), errors)).isNull();
        assertThat(errors).extracting(FieldViolation::field).contains("limitTokens", "softLimitPct");

        errors.clear();
        assertThat(BudgetAdminController.limits(req(null, 10L, "EURO", null, null), errors)).isNull();
        assertThat(errors).extracting(FieldViolation::field).containsExactly("currency");
    }
}
