package com.springaimcpservercommon.loadtest.discovery;

import com.springaimcpservercommon.loadtest.Fixtures;
import com.springaimcpservercommon.loadtest.model.ApiCatalog;
import com.springaimcpservercommon.loadtest.model.ApiEndpoint;
import com.springaimcpservercommon.loadtest.model.ApiParam;
import com.springaimcpservercommon.loadtest.model.HttpMethod;
import com.springaimcpservercommon.loadtest.model.ObjectSchema;
import com.springaimcpservercommon.loadtest.model.ParamLocation;
import com.springaimcpservercommon.loadtest.model.RefSchema;
import com.springaimcpservercommon.loadtest.model.ScalarSchema;
import com.springaimcpservercommon.loadtest.model.ScalarType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The Spring patterns the sample CRM uses, and the API-first / placeholder / root-wrapping cases. */
class SpringPatternsDiscoveryTest {

    private static final List<String> LOG = new ArrayList<>();
    private static ApiCatalog crm;

    @BeforeAll
    static void scan() {
        crm = CatalogMerger.merge(List.of(new SpringSourceScanner(LOG::add).scan(Fixtures.sampleCrm())));
    }

    private static ApiEndpoint op(String display) {
        return crm.endpoints().stream().filter(e -> e.displayName().equals(display)).findFirst()
                .orElseThrow(() -> new AssertionError(display + " not in " + crm.endpoints().stream()
                        .map(ApiEndpoint::displayName).toList()));
    }

    @Test
    void findsEveryKindOfSpringEndpoint() {
        assertThat(crm.endpoints()).extracting(ApiEndpoint::displayName).containsExactlyInAnyOrder(
                // generic AbstractCrudController<T, ID> inherited by two controllers using a composed @ApiController
                "GET /api/companies", "GET /api/companies/{id}", "POST /api/companies", "PUT /api/companies/{id}",
                "DELETE /api/companies/{id}",
                "GET /api/contacts", "GET /api/contacts/{id}", "POST /api/contacts", "PUT /api/contacts/{id}",
                "DELETE /api/contacts/{id}",
                "POST /api/users", "POST /api/deals", "GET /api/deals/{dealId}", "DELETE /api/deals/{dealId}",
                // base path only from the composed annotation's @RequestMapping
                "GET /api/info",
                // an @HttpExchange interface implemented by a @RestController
                "GET /api/status/version",
                // WebMvc.fn functional routes with .path() nesting
                "GET /api/health/ping", "GET /api/reports/{year}", "POST /api/reports/rebuild",
                // Spring Data REST (base path /rest; AppUserRepository is exported = false)
                "GET /rest/tasks", "POST /rest/tasks", "GET /rest/tasks/{id}", "PUT /rest/tasks/{id}",
                "PATCH /rest/tasks/{id}", "DELETE /rest/tasks/{id}");
        assertThat(LOG).anyMatch(l -> l.contains("3 functional route") && l.contains("6 Spring Data REST"));
    }

    @Test
    void genericControllersBindTheirTypeArguments() {
        assertThat(op("POST /api/companies").body()).isEqualTo(new RefSchema("Company"));
        assertThat(op("POST /api/contacts").body()).isEqualTo(new RefSchema("Contact"));
        ApiParam id = op("GET /api/companies/{id}").params(ParamLocation.PATH).getFirst();
        assertThat(((ScalarSchema) id.schema()).type()).isEqualTo(ScalarType.INTEGER); // ID → Long
        assertThat(op("GET /api/companies").id()).isEqualTo("listCompanies");
    }

    @Test
    void entityBodiesLeaveServerManagedFieldsOutAndReferenceRelatedEntitiesById() {
        ObjectSchema company = crm.schemas().get("Company");
        assertThat(company.properties().keySet()).containsExactly("name", "registrationNo", "website");
        assertThat(company.properties().get("name").required()).isTrue(); // @Column(nullable = false)
        assertThat(((ScalarSchema) company.properties().get("name").schema()).constraints().maxLength()).isEqualTo(60L);
        assertThat(((ScalarSchema) company.properties().get("website").schema()).constraints().maxLength())
                .isEqualTo(255L);

        ObjectSchema contact = crm.schemas().get("Contact");
        assertThat(contact.properties().keySet()).containsExactly("firstName", "lastName", "email", "company");
        assertThat(contact.properties().get("company").schema()).isEqualTo(new RefSchema("CompanyRef"));
        assertThat(contact.properties().get("company").required()).isTrue(); // @ManyToOne(optional = false)
        assertThat(crm.schemas().get("CompanyRef").properties().keySet()).containsExactly("id");
    }

    @Test
    void springDataRestBodiesUseResourceLinksAndDefaultPaths() {
        ObjectSchema task = crm.schemas().get("TaskResource");
        assertThat(task.properties().keySet()).containsExactly("summary", "dueDate"); // Deal is not exported
        assertThat(DataRestScanner.defaultPath("Company")).isEqualTo("companies");
        assertThat(DataRestScanner.defaultPath("AppUser")).isEqualTo("appUsers");
        assertThat(DataRestScanner.defaultPath("Address")).isEqualTo("addresses");
        assertThat(op("PATCH /rest/tasks/{id}").sources()).contains("data-rest");
    }

    @Test
    void entitiesCarryColumnFacts() {
        var contact = crm.entities().stream().filter(e -> e.entityName().equals("Contact")).findFirst().orElseThrow();
        assertThat(contact.table()).isEqualTo("contacts");
        assertThat(contact.columnLengths()).containsEntry("firstName", 40).containsEntry("email", 80);
        assertThat(contact.uniqueFields()).containsExactly("email");
        assertThat(contact.idGenerated()).isTrue();
        var deal = crm.entities().stream().filter(e -> e.entityName().equals("Deal")).findFirst().orElseThrow();
        assertThat(deal.fieldReferences()).containsEntry("owner", "AppUser");
        assertThat(deal.joinColumns()).containsEntry("owner", "owner_user_id");
    }

    @Test
    void apiFirstProjectsUseTheirBundledSpecAndGeneratedSourcesWithPlaceholders(@TempDir Path dir) throws IOException {
        Path res = Files.createDirectories(dir.resolve("src/main/resources"));
        Files.writeString(res.resolve("application.properties"), "server.servlet.context-path=/shop\n");
        Files.writeString(res.resolve("openapi.yml"), """
                openapi: 3.0.3
                info: {title: shop, version: '1'}
                servers: [{url: 'http://localhost:8080/shop/api'}]
                paths:
                  /orders: {get: {operationId: listOrders}}
                """);
        Files.writeString(res.resolve("messages.yml"), "greeting: hello\n");
        Path gen = Files.createDirectories(dir.resolve("target/generated-sources/openapi/src/main/java/x"));
        Files.writeString(gen.resolve("OrdersApi.java"), """
                package x;
                import org.springframework.web.bind.annotation.*;
                @RequestMapping("${openapi.shop.base-path:/api}")
                public interface OrdersApi {
                    @GetMapping("/orders/{orderId}")
                    Object getOrder(@PathVariable("orderId") Long orderId);
                }
                """);
        assertThat(SpringSourceScanner.bundledOpenApiSpecs(dir)).extracting(p -> p.getFileName().toString())
                .containsExactly("openapi.yml");
        ApiCatalog generated = new SpringSourceScanner(s -> { }).scan(dir);
        assertThat(generated.endpoints()).extracting(ApiEndpoint::displayName)
                .containsExactly("GET /api/orders/{orderId}");

        ApiCatalog spec = new OpenApiReader(s -> { }).read(Files.readString(res.resolve("openapi.yml")));
        assertThat(spec.basePath()).isEqualTo("/shop/api");
        ApiCatalog rebased = CatalogMerger.rebase(spec, "/api", "/shop");
        assertThat(rebased.basePath()).isEqualTo("/shop");
        assertThat(rebased.endpoints()).extracting(ApiEndpoint::path).containsExactly("/api/orders");
    }

    @Test
    void rootWrappedBodiesFollowUnwrapRootValue(@TempDir Path dir) throws IOException {
        Path res = Files.createDirectories(dir.resolve("src/main/resources"));
        Files.writeString(res.resolve("application.properties"),
                "spring.jackson.deserialization.UNWRAP_ROOT_VALUE=true\n");
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("UsersApi.java"), """
                package x;
                import com.fasterxml.jackson.annotation.JsonRootName;
                import org.springframework.web.bind.annotation.*;
                @RestController
                class UsersApi {
                    @PostMapping("/users/login")
                    Object login(@RequestBody LoginParam p) { return null; }
                    @PostMapping("/notes")
                    Object note(@RequestBody Note n) { return null; }
                }
                @JsonRootName("user")
                class LoginParam { String email; String password; }
                class Note { String text; }
                """);
        ApiCatalog c = new SpringSourceScanner(s -> { }).scan(dir);
        ObjectSchema login = (ObjectSchema) c.endpoints().stream().filter(e -> e.path().equals("/users/login"))
                .findFirst().orElseThrow().body();
        assertThat(login.properties().keySet()).containsExactly("user");
        assertThat(login.properties().get("user").schema()).isEqualTo(new RefSchema("LoginParam"));
        ObjectSchema note = (ObjectSchema) c.endpoints().stream().filter(e -> e.path().equals("/notes"))
                .findFirst().orElseThrow().body();
        assertThat(note.properties().keySet()).containsExactly("Note"); // no @JsonRootName: the class name
    }

    @Test
    void precisionAndScaleBecomeAMaximum() {
        // Deal.amount is @Column(precision = 12, scale = 2) but Deal is only reached through a DTO here;
        // check the mapper directly through a synthetic DTO-free path: the entity schema
        TypeMapperAccess.entitySchema(Fixtures.sampleCrm(), "Deal").ifPresent(deal -> {
            ScalarSchema amount = (ScalarSchema) deal.properties().get("amount").schema();
            assertThat(amount.constraints().maximum()).isEqualTo(new BigDecimal("9999999999"));
            assertThat(deal.properties().get("owner").schema()).isEqualTo(new RefSchema("AppUserRef"));
            assertThat(deal.properties()).doesNotContainKeys("id", "version", "createdAt", "updatedAt");
        });
        assertThat(TypeMapperAccess.entitySchema(Fixtures.sampleCrm(), "Deal")).isPresent();
    }

    @Test
    void anHttpExchangeInterfaceAloneIsAClientNotAnEndpoint(@TempDir Path dir) throws IOException {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("PaymentsClient.java"), """
                package x;
                import org.springframework.web.service.annotation.*;
                @HttpExchange("https://payments.example.com")
                interface PaymentsClient {
                    @PostExchange("/charges")
                    Object charge(Object body);
                }
                """);
        assertThat(new SpringSourceScanner(s -> { }).scan(dir).endpoints()).isEmpty();
        assertThat(HttpMethod.parse("RequestMethod.PATCH")).isEqualTo(HttpMethod.PATCH);
    }
}
