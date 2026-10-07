package com.springaimcpservercommon.loadtest.runtime;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The routes, shapes, constraints and access of a real Spring MVC context as an OpenAPI document. */
class RuntimeModelBuilderTest {

    private static AnnotationConfigWebApplicationContext context;
    private static Map<String, Object> model;

    @RestController
    @RequestMapping("/api/orders")
    public static class OrderController {
        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        OrderView create(@RequestBody NewOrder order) {
            return null;
        }

        @GetMapping("/{id:\\d+}")
        ResponseEntity<OrderView> get(@PathVariable Long id) {
            return null;
        }

        @GetMapping
        List<OrderView> search(@RequestParam(defaultValue = "10") int size, @RequestParam(required = false) String q,
                               OrderFilter filter, @RequestHeader("X-Tenant") String tenant,
                               @RequestHeader("Authorization") String authorization) {
            return null;
        }

        @PutMapping("/{id}")
        @PreAuthorize("hasRole('ADMIN') and #id > 0")
        OrderView update(@PathVariable Long id, @RequestBody NewOrder order) {
            return null;
        }

        @DeleteMapping("/{id}")
        @Secured({"ROLE_ADMIN", "ROLE_OPS"})
        void delete(@PathVariable Long id) {
        }

        @PostMapping(value = "/{id}/documents", consumes = "multipart/form-data")
        String upload(@PathVariable Long id, @RequestPart("file") MultipartFile file, @RequestPart("meta") DocMeta meta) {
            return null;
        }

        @GetMapping("/files/**")
        String wildcard() {
            return null;
        }

        @GetMapping("/open")
        @PermitAll
        public String open() {
            return null;
        }
    }

    record NewOrder(@NotBlank @Size(min = 2, max = 40) String product, @Min(1) int quantity,
                    @NotNull @Pattern(regexp = "[A-Z]{3}") String currency, @Email String contact,
                    @JsonProperty("ref") String reference, @JsonIgnore String internal, List<String> tags) {
    }

    record OrderView(Long id, int quantity, BigDecimal total, Status status, Instant placedAt, List<Line> lines,
                     @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String secret) {
    }

    record Line(String sku, double price) {
    }

    enum Status { NEW, PAID }

    record DocMeta(String title) {
    }

    static class OrderFilter {
        private String status;
        private Integer minQuantity;

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Integer getMinQuantity() {
            return minQuantity;
        }

        public void setMinQuantity(Integer minQuantity) {
            this.minQuantity = minQuantity;
        }
    }

    @Configuration
    @EnableWebMvc
    public static class Config {
        @Bean
        OrderController orderController() {
            return new OrderController();
        }
    }

    @BeforeAll
    static void start() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Config.class);
        context.refresh();
        model = new RuntimeModelBuilder(new LoadTestRuntimeProperties(true, false, null, null))
                .build("shop", "/shop", List.of(context.getBean(RequestMappingHandlerMapping.class)));
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object o) {
        return (List<Map<String, Object>>) o;
    }

    private static Map<String, Object> op(String path, String method) {
        return map(map(map(model.get("paths")).get(path)).get(method));
    }

    @Test
    void routesCarryTheContextPathAndLoseRegexesAndWildcards() {
        assertThat(model.get("openapi")).isEqualTo("3.0.3");
        assertThat(list(model.get("servers")).get(0)).containsEntry("url", "/shop");
        assertThat(map(model.get("paths")).keySet()).containsExactlyInAnyOrder("/api/orders", "/api/orders/{id}",
                "/api/orders/{id}/documents", "/api/orders/open")
                .as("/files/** cannot be called as it is").doesNotContain("/api/orders/files/**");
        assertThat(map(model.get("x-loadtest")).get("routes")).isEqualTo(7);
        assertThat(op("/api/orders/{id}", "get").get("x-loadtest-handler")).asString().endsWith("OrderController#get");
    }

    @Test
    void parametersCoverPathQueryHeaderAndQueryObjectsAndSkipAuthorization() {
        List<Map<String, Object>> params = list(op("/api/orders", "get").get("parameters"));
        assertThat(params).extracting(p -> p.get("in") + ":" + p.get("name")).containsExactly("query:size", "query:q",
                "query:status", "query:minQuantity", "header:X-Tenant");
        assertThat(map(params.get(0).get("schema"))).containsEntry("type", "integer").containsEntry("default", "10");
        assertThat(params.get(0)).as("a default makes it optional").containsEntry("required", false);
        assertThat(params.get(1)).containsEntry("required", false);
        assertThat(params.get(4)).containsEntry("required", true);
        List<Map<String, Object>> path = list(op("/api/orders/{id}", "get").get("parameters"));
        assertThat(path.get(0)).containsEntry("in", "path").containsEntry("required", true);
        assertThat(map(path.get(0).get("schema"))).containsEntry("format", "int64");
    }

    @Test
    void aRequestBodyIsTheJacksonShapeWithBeanValidationConstraints() {
        Map<String, Object> content = map(map(op("/api/orders", "post").get("requestBody")).get("content"));
        Map<String, Object> schema = map(map(content.get("application/json")).get("schema"));
        Map<String, Object> props = map(schema.get("properties"));
        assertThat(props.keySet()).as("renamed, ignored members honoured").containsExactly("product", "quantity", "currency",
                "contact", "ref", "tags");
        assertThat(map(props.get("product"))).containsEntry("minLength", 2).containsEntry("maxLength", 40);
        assertThat(map(props.get("quantity"))).containsEntry("type", "integer").containsEntry("minimum", 1L);
        assertThat(map(props.get("currency"))).containsEntry("pattern", "[A-Z]{3}");
        assertThat(map(props.get("contact"))).containsEntry("format", "email");
        assertThat(map(props.get("tags"))).containsEntry("type", "array");
        assertThat((List<String>) schema.get("required")).containsExactlyInAnyOrder("product", "currency");
    }

    @Test
    void aResponseIsTheSuccessShapeWithTheDeclaredStatusAndWrappersLookedThrough() {
        Map<String, Object> created = map(op("/api/orders", "post").get("responses"));
        assertThat(created.keySet()).containsExactly("201");
        Map<String, Object> view = map(map(map(map(created.get("201")).get("content")).get("application/json")).get("schema"));
        Map<String, Object> props = map(view.get("properties"));
        assertThat(props.keySet()).as("write-only members are never in a response")
                .containsExactly("id", "quantity", "total", "status", "placedAt", "lines");
        assertThat(map(props.get("status")).get("enum")).isEqualTo(List.of("NEW", "PAID"));
        assertThat(map(props.get("placedAt"))).containsEntry("format", "date-time");
        assertThat(map(map(props.get("lines")).get("items")).get("properties")).isNotNull();
        assertThat((List<String>) view.get("required")).as("only primitives are always serialised").containsExactly("quantity");
        Map<String, Object> one = map(map(map(map(op("/api/orders/{id}", "get").get("responses")).get("200")).get("content")).get("application/json"));
        assertThat(map(one.get("schema")).get("type")).as("ResponseEntity<OrderView> → OrderView").isEqualTo("object");
        assertThat(map(map(op("/api/orders/{id}", "delete").get("responses")).get("200"))).as("void: no body").doesNotContainKey("content");
        assertThat(map(map(op("/api/orders", "get").get("responses")).get("200")).get("content")).asString().contains("array");
    }

    @Test
    void uploadsAreMultipartWithFilesAsBinary() {
        Map<String, Object> body = map(map(op("/api/orders/{id}/documents", "post").get("requestBody")).get("content"));
        Map<String, Object> props = map(map(map(body.get("multipart/form-data")).get("schema")).get("properties"));
        assertThat(map(props.get("file"))).containsEntry("format", "binary");
        assertThat(map(props.get("meta"))).containsEntry("type", "object");
    }

    @Test
    void methodSecurityBecomesTheAccessOfTheOperation() {
        assertThat(op("/api/orders/{id}", "put").get("x-loadtest-access"))
                .isEqualTo(Map.of("kind", "ROLES", "roles", List.of("ADMIN")));
        assertThat(op("/api/orders/{id}", "delete").get("x-loadtest-access"))
                .isEqualTo(Map.of("kind", "ROLES", "roles", List.of("ADMIN", "OPS")));
        assertThat(op("/api/orders/open", "get").get("x-loadtest-access")).isEqualTo(Map.of("kind", "PUBLIC", "roles", List.of()));
        assertThat(op("/api/orders", "post")).as("nothing says: unknown").doesNotContainKey("x-loadtest-access");
        assertThat(RuntimeAccess.expression("isAuthenticated()")).isEqualTo(Map.of("kind", "AUTHENTICATED", "roles", List.of()));
        assertThat(RuntimeAccess.expression("hasAnyAuthority('SCOPE_read', \"SCOPE_write\")"))
                .isEqualTo(Map.of("kind", "ROLES", "roles", List.of("SCOPE_read", "SCOPE_write")));
        assertThat(RuntimeAccess.expression("denyAll()")).isEqualTo(Map.of("kind", "DENIED", "roles", List.of()));
        assertThat(RuntimeAccess.expression("#id == principal.id")).isNull();
    }

    @Test
    void excludedPackagesAndPathsAreLeftOut() {
        Map<String, Object> m = new RuntimeModelBuilder(new LoadTestRuntimeProperties(true, false, List.of(OrderController.class.getPackageName()),
                null)).build("shop", "", List.of(context.getBean(RequestMappingHandlerMapping.class)));
        assertThat(map(m.get("paths"))).isEmpty();
        assertThat(m).doesNotContainKey("servers");
        Map<String, Object> noOrders = new RuntimeModelBuilder(new LoadTestRuntimeProperties(true, false, null, List.of("/api/orders")))
                .build("shop", "", List.of(context.getBean(RequestMappingHandlerMapping.class)));
        assertThat(map(noOrders.get("paths"))).isEmpty();
    }
}
