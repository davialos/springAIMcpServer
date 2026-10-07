package com.springaimcpservercommon.loadtest.k6;

import com.springaimcpservercommon.loadtest.data.DataPlan;
import com.springaimcpservercommon.loadtest.data.FieldKind;
import com.springaimcpservercommon.loadtest.data.FieldPlan;
import com.springaimcpservercommon.loadtest.data.PoolRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BindingReportTest {

    private static final PoolRef CUSTOMERS = PoolRef.parse("customers.id");
    private static final PoolRef NEW_ORDERS = PoolRef.parse("sql:SELECT id FROM orders WHERE status = 'NEW'");
    private static final PoolRef EMPTY = PoolRef.parse("coupons.code");

    private static DataPlan plan() {
        return new DataPlan(Map.of(
                "createOrder.body.customerId", new FieldPlan("createOrder.body.customerId", "customerId", "createOrder",
                        FieldKind.ID, CUSTOMERS, false),
                "cancelOrder.path.id", new FieldPlan("cancelOrder.path.id", "id", "cancelOrder", FieldKind.ID,
                        NEW_ORDERS, false),
                "applyCoupon.body.code", new FieldPlan("applyCoupon.body.code", "code", "applyCoupon", FieldKind.CODE,
                        EMPTY, false),
                "getWarehouse.path.warehouseId", new FieldPlan("getWarehouse.path.warehouseId", "warehouseId",
                        "getWarehouse", FieldKind.ID, null, false),
                "login.body.password", new FieldPlan("login.body.password", "password", "login", FieldKind.PASSWORD,
                        null, true),
                "createOrder.body.note", new FieldPlan("createOrder.body.note", "note", "createOrder", FieldKind.TEXT,
                        null, false)));
    }

    @Test
    void countsBoundEmptyAndUnboundIdentifiers() {
        var pools = Map.<String, List<Object>>of(CUSTOMERS.key(), List.of(1L, 2L), NEW_ORDERS.key(), List.of(7L));
        BindingReport.Summary s = BindingReport.summarize(plan(), pools, Set.of());
        assertThat(s.bound()).isEqualTo(2);
        assertThat(s.boundWithoutValues()).isEqualTo(1); // coupons.code has no values
        assertThat(s.unboundIdentifiers()).isEqualTo(1); // warehouseId; password and note are not identifiers
    }

    @Test
    void seededPoolsCountAsBoundAndTheReportNamesQueriesAndTheGaps() {
        var pools = Map.<String, List<Object>>of(CUSTOMERS.key(), List.of(1L), NEW_ORDERS.key(), List.of(7L, 8L));
        String md = BindingReport.render(plan(), pools, Set.of(EMPTY.key()));
        assertThat(BindingReport.summarize(plan(), pools, Set.of(EMPTY.key())).boundWithoutValues()).isZero();
        assertThat(md).contains("## Inputs that will not hit existing rows")
                .contains("| `getWarehouse.path.warehouseId` | id | no table or column matched |")
                .contains("| `cancelOrder.path.id` | query `" + NEW_ORDERS.key() + "` — `SELECT id FROM orders WHERE status = 'NEW'` | 2 |")
                .contains("| `createOrder.body.customerId` | column `customers.id` | 1 |")
                .contains("| `applyCoupon.body.code` | column `coupons.code`; plus rows created in setup | setup |")
                .doesNotContain("login.body.password").doesNotContain("createOrder.body.note");
    }

    @Test
    void omitsTheGapSectionWhenEverythingIsBound() {
        DataPlan ok = new DataPlan(Map.of("a.id", new FieldPlan("a.id", "id", "a", FieldKind.ID, CUSTOMERS, false)));
        assertThat(BindingReport.render(ok, Map.of(CUSTOMERS.key(), List.of(1L)), Set.of()))
                .doesNotContain("will not hit existing rows");
    }
}
