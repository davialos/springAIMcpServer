package com.springaimcpservercommon.loadtest.discovery;

import com.springaimcpservercommon.loadtest.Fixtures;
import com.springaimcpservercommon.loadtest.model.ApiCatalog;
import com.springaimcpservercommon.loadtest.model.ApiEndpoint;
import com.springaimcpservercommon.loadtest.model.ApiParam;
import com.springaimcpservercommon.loadtest.model.ArraySchema;
import com.springaimcpservercommon.loadtest.model.Constraints;
import com.springaimcpservercommon.loadtest.model.EntityTable;
import com.springaimcpservercommon.loadtest.model.HttpMethod;
import com.springaimcpservercommon.loadtest.model.ObjectSchema;
import com.springaimcpservercommon.loadtest.model.ParamLocation;
import com.springaimcpservercommon.loadtest.model.RefSchema;
import com.springaimcpservercommon.loadtest.model.ScalarSchema;
import com.springaimcpservercommon.loadtest.model.ScalarType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpringSourceScannerTest {

    private static final List<String> LOG = new ArrayList<>();
    private static ApiCatalog catalog;

    @BeforeAll
    static void scan() {
        catalog = CatalogMerger.merge(List.of(new SpringSourceScanner(LOG::add).scan(Fixtures.sampleShop())));
    }

    private static ApiEndpoint endpoint(HttpMethod method, String path) {
        return catalog.endpoints().stream().filter(e -> e.method() == method && e.path().equals(path)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + method + " " + path + " in " + catalog.endpoints()));
    }

    private static ApiParam param(ApiEndpoint e, ParamLocation in, String name) {
        return e.params().stream().filter(p -> p.in() == in && p.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + in + " " + name + " in " + e.params()));
    }

    @Test
    void findsEveryMappedOperationWithConstantPathsAndContextPath() {
        assertThat(catalog.basePath()).isEqualTo("/shop");
        assertThat(catalog.endpoints()).extracting(ApiEndpoint::displayName).containsExactlyInAnyOrder(
                "GET /api/v1/customers", "GET /api/v1/customers/{id}", "POST /api/v1/customers",
                "PUT /api/v1/customers/{id}", "DELETE /api/v1/customers/{id}", "GET /api/v1/orders/{orderId}",
                "GET /api/v1/orders/search", "POST /api/v1/orders", "POST /api/v1/orders/{orderId}/attachments",
                "GET /api/v1/products",
                "GET /api/v1/products/{sku}");
    }

    @Test
    void genericHandlerNamesGetTheControllerSubjectAndOperationIdWins() {
        assertThat(endpoint(HttpMethod.GET, "/api/v1/customers/{id}").id()).isEqualTo("getCustomer");
        assertThat(endpoint(HttpMethod.POST, "/api/v1/orders").id()).isEqualTo("createOrder");
        assertThat(endpoint(HttpMethod.GET, "/api/v1/customers").id()).isEqualTo("listCustomers");
        assertThat(endpoint(HttpMethod.GET, "/api/v1/customers").summary()).isEqualTo("Page through customers");
    }

    @Test
    void multipartUploadsBecomeBinaryFieldsOfAMultipartBody() {
        ApiEndpoint upload = endpoint(HttpMethod.POST, "/api/v1/orders/{orderId}/attachments");
        assertThat(upload.bodyType()).isEqualTo("multipart");
        ObjectSchema body = (ObjectSchema) upload.body();
        assertThat(((ScalarSchema) body.properties().get("file").schema()).format()).isEqualTo("binary");
        assertThat(upload.params()).extracting(ApiParam::name).containsExactly("orderId");
    }

    @Test
    void mapsParametersPageableHeadersAndQueryObjects() {
        ApiEndpoint list = endpoint(HttpMethod.GET, "/api/v1/customers");
        assertThat(list.params()).extracting(ApiParam::name).containsExactly("page", "size", "email");
        assertThat(param(list, ParamLocation.QUERY, "email").required()).isFalse();

        ApiEndpoint update = endpoint(HttpMethod.PUT, "/api/v1/customers/{id}");
        assertThat(param(update, ParamLocation.PATH, "id").required()).isTrue();
        assertThat(param(update, ParamLocation.HEADER, "X-Request-Id").required()).isFalse();

        ApiEndpoint search = endpoint(HttpMethod.GET, "/api/v1/orders/search");
        assertThat(search.params()).extracting(ApiParam::name).containsExactly("status", "customerId", "limit");
        assertThat(((ScalarSchema) param(search, ParamLocation.QUERY, "status").schema()).enumValues())
                .containsExactly("NEW", "PAID", "SHIPPED", "CANCELLED");
        assertThat(param(search, ParamLocation.QUERY, "limit").defaultValue()).isEqualTo("20");
    }

    @Test
    void pathVariableRegexBecomesAPatternConstraint() {
        ApiParam orderId = param(endpoint(HttpMethod.GET, "/api/v1/orders/{orderId}"), ParamLocation.PATH, "orderId");
        assertThat(((ScalarSchema) orderId.schema()).constraints().pattern()).isEqualTo("^\\d+$");
    }

    @Test
    void apiInterfaceMappingsAreFound() {
        ApiEndpoint products = endpoint(HttpMethod.GET, "/api/v1/products");
        assertThat(param(products, ParamLocation.QUERY, "q").required()).isFalse();
        assertThat(endpoint(HttpMethod.GET, "/api/v1/products/{sku}").params()).extracting(ApiParam::name)
                .containsExactly("sku");
    }

    @Test
    void requestDtosCarryValidationJacksonAndOpenApiAnnotations() {
        assertThat(endpoint(HttpMethod.POST, "/api/v1/customers").body()).isEqualTo(new RefSchema("CreateCustomerRequest"));
        ObjectSchema customer = catalog.schemas().get("CreateCustomerRequest");
        assertThat(customer.properties().keySet()).containsExactly("firstName", "lastName", "email", "phone",
                "birthDate", "password", "address", "marketing_opt_in", "tags");
        ScalarSchema firstName = (ScalarSchema) customer.properties().get("firstName").schema();
        assertThat(customer.properties().get("firstName").required()).isTrue();
        assertThat(firstName.constraints()).isEqualTo(new Constraints(1L, 40L, null, null, null, null));
        assertThat(((ScalarSchema) customer.properties().get("email").schema()).format()).isEqualTo("email");
        assertThat(((ScalarSchema) customer.properties().get("phone").schema()).example()).isEqualTo("+14155550100");
        assertThat(((ScalarSchema) customer.properties().get("birthDate").schema()).constraints().temporal())
                .isEqualTo(Constraints.Temporal.PAST);
        assertThat(customer.properties().get("password").sensitive()).isTrue();
        assertThat(((ArraySchema) customer.properties().get("tags").schema()).maxItems()).isEqualTo(5);
        assertThat(catalog.schemas().get("Address").properties().get("zipCode").schema())
                .isEqualTo(ScalarSchema.of(ScalarType.STRING, null).withConstraints(
                        new Constraints(5L, 5L, null, null, null, null)));

        ObjectSchema order = catalog.schemas().get("CreateOrderRequest");
        assertThat(((ArraySchema) order.properties().get("lines").schema()).minItems()).isEqualTo(1);
        assertThat(((ScalarSchema) order.properties().get("couponCode").schema()).constraints().pattern())
                .isEqualTo("^[A-Z]{3}-\\d{4}$");
        ScalarSchema quantity = (ScalarSchema) catalog.schemas().get("OrderLine").properties().get("quantity").schema();
        assertThat(quantity.constraints().minimum()).isEqualTo(BigDecimal.ONE);
        assertThat(quantity.constraints().maximum()).isEqualTo(new BigDecimal("99"));
        assertThat(catalog.schemas().get("OrderLine").properties()).doesNotContainKey("serialVersionUID");
    }

    @Test
    void jpaEntitiesMapToTablesColumnsAndJoinColumns() {
        EntityTable customer = entity("Customer");
        assertThat(customer.table()).isEqualTo("customers");
        assertThat(customer.idField()).isEqualTo("id"); // inherited from the @MappedSuperclass
        assertThat(customer.fieldColumns()).containsEntry("firstName", "first_name").containsEntry("lastName", "last_name");
        assertThat(customer.sensitiveFields()).contains("passwordHash");

        EntityTable order = entity("PurchaseOrder");
        assertThat(order.table()).isEqualTo("orders");
        assertThat(order.fieldReferences()).containsEntry("customer", "Customer");
        assertThat(order.joinColumns()).containsEntry("customer", "customer_id");
        assertThat(order.fieldColumns()).doesNotContainKey("lines");

        assertThat(entity("Product").table()).isEqualTo("product");
        assertThat(entity("Product").idColumn()).isEqualTo("sku");
    }

    @Test
    void controllerSubjectBecomesTheResource() {
        assertThat(endpoint(HttpMethod.GET, "/api/v1/customers/{id}").resource()).isEqualTo("Customer");
        assertThat(endpoint(HttpMethod.GET, "/api/v1/products/{sku}").resource()).isEqualTo("Product");
    }

    private static EntityTable entity(String name) {
        return catalog.entities().stream().filter(e -> e.entityName().equals(name)).findFirst().orElseThrow();
    }
}
