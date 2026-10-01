package com.springaimcpservercommon.loadtest.data;

import com.springaimcpservercommon.loadtest.Fixtures;
import com.springaimcpservercommon.loadtest.discovery.CatalogMerger;
import com.springaimcpservercommon.loadtest.discovery.SpringSourceScanner;
import com.springaimcpservercommon.loadtest.model.ApiCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DataPlanTest {

    private static final ApiCatalog CATALOG =
            CatalogMerger.merge(List.of(new SpringSourceScanner(s -> { }).scan(Fixtures.sampleShop())));

    private static DataPlan plan(List<DbTable> db, Map<String, PoolRef> explicit) {
        return DataPlan.build(CATALOG, new RealDataBinder(new TableIndex(CATALOG.entities(), db), explicit));
    }

    private static String pool(DataPlan plan, String key) {
        FieldPlan f = plan.field(key);
        assertThat(f).as(key).isNotNull();
        return f.pool() == null ? null : f.pool().key();
    }

    @Test
    void bindsIdentifiersForeignKeysNaturalKeysAndQueryFiltersToEntityTables() {
        DataPlan plan = plan(List.of(), Map.of());
        assertThat(pool(plan, "getCustomer.path.id")).isEqualTo("customers.id");
        assertThat(pool(plan, "getOrder.path.orderId")).isEqualTo("orders.id");
        assertThat(pool(plan, "CreateOrderRequest.customerId")).isEqualTo("customers.id");
        assertThat(pool(plan, "searchOrders.query.customerId")).isEqualTo("customers.id");
        assertThat(pool(plan, "OrderLine.productSku")).isEqualTo("product.sku");
        assertThat(pool(plan, "getProduct.path.sku")).isEqualTo("product.sku");
        assertThat(pool(plan, "listCustomers.query.email")).isEqualTo("customers.email");
    }

    @Test
    void leavesCreatePayloadsSensitiveFieldsAndPagingGenerated() {
        DataPlan plan = plan(List.of(), Map.of());
        assertThat(pool(plan, "CreateCustomerRequest.email")).isNull(); // unique column on create: generated
        assertThat(pool(plan, "CreateCustomerRequest.password")).isNull();
        assertThat(plan.field("CreateCustomerRequest.password").sensitive()).isTrue();
        assertThat(pool(plan, "listCustomers.query.page")).isNull();
        assertThat(plan.field("Address.city").kind()).isEqualTo(FieldKind.CITY);
    }

    @Test
    void databaseMetadataDecidesWhatExistsAndExplicitBindingsWin() {
        DbTable customers = new DbTable("public", "customers", Map.of("id", "int8", "email", "text"), List.of("id"));
        DataPlan plan = plan(List.of(customers), Map.of("*.deliveryNotes", PoolRef.parse("customers.email")));
        assertThat(pool(plan, "getCustomer.path.id")).isEqualTo("customers.id");
        assertThat(pool(plan, "getOrder.path.orderId")).isNull(); // no orders table in this database
        assertThat(pool(plan, "CreateOrderRequest.deliveryNotes")).isEqualTo("customers.email");
    }

    @Test
    void withoutAnyTableNothingIsBound() {
        DataPlan plan = DataPlan.build(CATALOG, new RealDataBinder(new TableIndex(List.of(), List.of()), Map.of()));
        assertThat(plan.pools()).isEmpty();
        assertThat(plan.fields()).isNotEmpty();
    }

    @Test
    void schemaResourceStripsCommandSuffixes() {
        assertThat(DataPlan.schemaResource("CreateOrderRequest")).isEqualTo("Order");
        assertThat(DataPlan.schemaResource("CustomerDto")).isEqualTo("Customer");
        assertThat(DataPlan.segmentBefore("/a/orders/{id}/lines/{lineId}", "id", null)).isEqualTo("orders");
    }
}
