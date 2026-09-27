package com.springaimcpservercommon.persistence.usage;

import com.springaimcpservercommon.persistence.config.ConfigStore;
import com.springaimcpservercommon.persistence.config.DraftContent;
import com.springaimcpservercommon.persistence.config.ResourceKind;
import com.springaimcpservercommon.persistence.config.support.DaiTestDatabase;
import com.springaimcpservercommon.persistence.config.support.MutableClock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsageIT {

    private static final Instant T0 = Instant.parse("2026-09-28T10:15:00Z");

    private static DaiTestDatabase db;
    private static MutableClock clock;
    private static UsageLedger ledger;
    private static UUID workspace;
    private static UUID agent;
    private static UUID user;

    @BeforeAll
    static void setUp() {
        db = DaiTestDatabase.create();
        clock = new MutableClock(T0);
        ledger = new UsageLedger(db.store(), clock);
        workspace = db.workspace("usage");
        user = db.user("erin");
        agent = new ConfigStore(db.store()).createResource(workspace, ResourceKind.AGENT, "support-bot",
                DraftContent.of("{\"model\":\"m\"}"), user).resourceId();
    }

    @AfterAll
    static void tearDown() {
        db.close();
    }

    private static UsageDelta delta(Instant at, UUID agentId, UUID principalId, long input, long costMicros) {
        return new UsageDelta(at, workspace, agentId, principalId, "openai", "gpt-x", "EUR", 1, input, 10, 0, costMicros);
    }

    private static long rows(String where) {
        Long count = db.jdbc().queryForObject("SELECT count(*) FROM " + db.table("dai_usage_hourly") + " WHERE " + where,
                Long.class, workspace);
        return count == null ? 0 : count;
    }

    @Test
    void upsertAccumulatesIntoOneHourlyRowEvenWithNullKeyColumns() {
        // agent and principal are NULL: only UNIQUE NULLS NOT DISTINCT makes the ON CONFLICT hit the same row
        ledger.record(new UsageDelta(T0, workspace, null, null, "anthropic", "claude", null, 1, 100, 20, 5, 0));
        ledger.record(new UsageDelta(T0.plusSeconds(1200), workspace, null, null, "anthropic", "claude", null,
                2, 50, 10, 0, 0));

        assertThat(rows("workspace_id = ? AND provider = 'anthropic'")).isEqualTo(1);
        UsageTotals totals = ledger.totals(new BudgetTarget.Workspace(workspace),
                new UsageWindow(T0.minus(Duration.ofHours(1)), T0.plus(Duration.ofHours(1))));
        assertThat(totals.calls()).isGreaterThanOrEqualTo(3);
        assertThat(totals.costMicrosByCurrency()).doesNotContainKey("null");
    }

    @Test
    void batchesMergeBucketsAndTotalsFilterByTarget() {
        Instant day = Instant.parse("2026-09-27T05:30:00Z");
        UUID other = db.user("frank");
        ledger.recordAll(List.of(
                delta(day, agent, user, 100, 1_000),
                delta(day.plusSeconds(60), agent, user, 100, 1_000),
                delta(day.plus(Duration.ofHours(3)), agent, other, 50, 500),
                delta(day.plus(Duration.ofDays(1)), agent, user, 1_000, 10_000)));

        UsageWindow sept27 = BudgetPeriod.DAY.windowContaining(day);
        UsageTotals agentDay = ledger.totals(new BudgetTarget.Agent(workspace, agent), sept27);
        assertThat(agentDay.calls()).isEqualTo(3);
        assertThat(agentDay.inputTokens()).isEqualTo(250);
        assertThat(agentDay.outputTokens()).isEqualTo(30);
        assertThat(agentDay.totalTokens()).isEqualTo(280);
        assertThat(agentDay.costMicros("EUR")).isEqualTo(2_500);

        UsageTotals userDay = ledger.totals(new BudgetTarget.Principal(null, user), sept27);
        assertThat(userDay.calls()).isEqualTo(2);
        assertThat(userDay.costMicros("EUR")).isEqualTo(2_000);

        Long hourRows = db.jdbc().queryForObject("SELECT count(*) FROM " + db.table("dai_usage_hourly")
                + " WHERE workspace_id = ? AND principal_id = ? AND bucket_start = ?", Long.class,
                workspace, user, Instant.parse("2026-09-27T05:00:00Z").atOffset(ZoneOffset.UTC));
        assertThat(hourRows).isEqualTo(1);
    }

    @Test
    void bucketsAreAcceptedWhateverTheSessionTimeZone() {
        // An outer transaction switches its session to +05:30; the ledger joins it (propagation REQUIRED, same
        // connection) and must pin TimeZone=UTC locally, otherwise ck_usage_hourly_bucket rejects the UTC hour bucket.
        db.store().transactions().executeWithoutResult(status -> {
            db.store().entityManager().createNativeQuery("SELECT set_config('TimeZone', 'Asia/Kolkata', true)")
                    .getSingleResult();
            ledger.record(delta(Instant.parse("2026-09-26T23:59:59Z"), null, null, 1, 1));
        });
        assertThat(ledger.totals(new BudgetTarget.Global(), BudgetPeriod.DAY.windowContaining(
                Instant.parse("2026-09-26T12:00:00Z"))).calls()).isEqualTo(1);
    }

    @Test
    void pricesAreVersionedByValidFrom() {
        PriceStore prices = new PriceStore(db.store(), clock);
        prices.addPrice("openai", "gpt-x", Instant.parse("2026-01-01T00:00:00Z"), "USD", 2_000_000, 8_000_000, 500_000, user);
        prices.addPrice("openai", "gpt-x", Instant.parse("2026-07-01T00:00:00Z"), "USD", 1_000_000, 4_000_000, 250_000, user);

        assertThat(prices.priceAt("openai", "gpt-x", Instant.parse("2026-03-01T00:00:00Z")).orElseThrow()
                .inputPerMtokMicros()).isEqualTo(2_000_000);
        ModelPriceView current = prices.priceAt("openai", "gpt-x", T0).orElseThrow();
        assertThat(current.inputPerMtokMicros()).isEqualTo(1_000_000);
        assertThat(current.currency()).isEqualTo("USD");
        assertThat(current.costMicros(1_000, 500, 0)).isEqualTo(3_000);
        assertThat(prices.priceAt("openai", "gpt-x", Instant.parse("2025-12-31T00:00:00Z"))).isEmpty();
    }

    @Test
    void budgetsAreUniqueWithNullsNotDistinctAndApplicableByScope() {
        BudgetStore budgets = new BudgetStore(db.store(), clock);
        BudgetView global = budgets.create(new BudgetTarget.Global(), BudgetPeriod.DAY, BudgetLimits.tokens(1_000_000), user);
        // all three id columns are NULL for GLOBAL budgets: only NULLS NOT DISTINCT rejects the duplicate
        assertThatThrownBy(() -> budgets.create(new BudgetTarget.Global(), BudgetPeriod.DAY,
                BudgetLimits.tokens(5), user))
                .satisfies(e -> assertThat(DaiTestDatabase.messages(e)).contains("uq_budget"));

        BudgetView ws = budgets.create(new BudgetTarget.Workspace(workspace), BudgetPeriod.MONTH,
                BudgetLimits.cost(50_000_000, "EUR"), user);
        BudgetView agentBudget = budgets.create(new BudgetTarget.Agent(workspace, agent), BudgetPeriod.DAY,
                BudgetLimits.tokens(10_000), user);
        BudgetView userBudget = budgets.create(new BudgetTarget.Principal(null, user), BudgetPeriod.DAY,
                BudgetLimits.tokens(2_000), user);

        assertThat(budgets.applicable(workspace, agent, user)).extracting(BudgetView::id)
                .contains(global.id(), ws.id(), agentBudget.id(), userBudget.id());
        assertThat(budgets.applicable(workspace, null, null)).extracting(BudgetView::id)
                .contains(global.id(), ws.id())
                .doesNotContain(agentBudget.id(), userBudget.id());

        budgets.setEnabled(userBudget.id(), false, user);
        assertThat(budgets.applicable(workspace, agent, user)).extracting(BudgetView::id).doesNotContain(userBudget.id());

        BudgetView changed = budgets.changeLimits(ws.id(), ws.rowVersion(), BudgetLimits.cost(60_000_000, "EUR"), user);
        assertThat(changed.limits().limitCostMicros()).isEqualTo(60_000_000);
        assertThat(ledger.currentPeriodTotals(agentBudget)).isNotNull();
        assertThat(budgets.delete(global.id())).isTrue();
    }
}
