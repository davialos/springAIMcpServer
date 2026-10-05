package com.springaimcpservercommon.loadtest.discovery;

import com.springaimcpservercommon.loadtest.model.Access;
import com.springaimcpservercommon.loadtest.model.ApiCatalog;
import com.springaimcpservercommon.loadtest.model.ApiEndpoint;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Spring Security setup read from the sources: the filter chain's matchers and method security annotations. */
class SecurityScannerTest {

    @TempDir
    static Path dir;
    private static SecurityModel model;
    private static List<ApiEndpoint> endpoints;

    @BeforeAll
    static void scan() throws IOException {
        Path src = Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.writeString(src.resolve("SecurityConfig.java"), """
                package demo;
                @Configuration
                @EnableWebSecurity
                class SecurityConfig {
                    @Bean
                    SecurityFilterChain chain(HttpSecurity http) throws Exception {
                        http.csrf(csrf -> csrf.disable())
                            .authorizeHttpRequests(a -> a
                                .requestMatchers("/public/**", "/health").permitAll()
                                .requestMatchers(HttpMethod.DELETE, "/items/**").hasRole("ADMIN")
                                .requestMatchers("/reports/**").hasAnyRole("AUDITOR", "ADMIN")
                                .anyRequest().authenticated())
                            .formLogin(f -> f.loginPage("/signin"))
                            .httpBasic(Customizer.withDefaults());
                        return http.build();
                    }
                }
                """);
        Files.writeString(src.resolve("DemoController.java"), """
                package demo;
                @RestController
                class DemoController {
                    @GetMapping("/items/{id}") Object item(@PathVariable Long id) { return null; }
                    @DeleteMapping("/items/{id}") void delete(@PathVariable Long id) { }
                    @PreAuthorize("hasRole('MANAGER') and #id > 0")
                    @PutMapping("/items/{id}") Object update(@PathVariable Long id, @RequestBody Item i) { return null; }
                    @GetMapping("/public/ping") Object ping() { return null; }
                    @GetMapping("/reports/daily") Object daily() { return null; }
                    @Secured("ROLE_SUPPORT") @GetMapping("/support") Object support() { return null; }
                    @PreAuthorize("permitAll()") @GetMapping("/open") Object open() { return null; }
                    @PreAuthorize("isAuthenticated()") @GetMapping("/me") Object me() { return null; }
                    @GetMapping("/other") Object other() { return null; }
                }
                record Item(String name) { }
                """);
        model = new SecurityScanner(new ArrayList<>()::add).scan(dir, ProjectSettings.read(dir));
        ApiCatalog c = new SpringSourceScanner(new ArrayList<>()::add).scan(dir);
        endpoints = model.annotate(c.endpoints());
    }

    private static Access access(String route) {
        return endpoints.stream().filter(e -> e.displayName().equals(route)).findFirst()
                .orElseThrow(() -> new AssertionError(route + " in " + endpoints)).access();
    }

    @Test
    void readsTheAuthenticationStyleCsrfAndLoginPage() {
        assertThat(model.style()).as("httpBasic is scriptable, form login is not preferred").isEqualTo(SecurityModel.Style.BASIC);
        assertThat(model.csrf()).isFalse();
        assertThat(model.loginPage()).isEqualTo("/signin");
        assertThat(model.rules()).hasSize(4);
    }

    @Test
    void methodSecurityAnnotationsGiveRoles() {
        assertThat(access("PUT /items/{id}").roles()).as("a combined expression keeps its roles").containsExactly("MANAGER");
        assertThat(access("GET /support").roles()).as("ROLE_ prefix dropped").containsExactly("SUPPORT");
        assertThat(access("GET /open").kind()).isEqualTo(Access.Kind.PUBLIC);
        assertThat(access("GET /me").kind()).isEqualTo(Access.Kind.AUTHENTICATED);
    }

    @Test
    void filterChainMatchersApplyInOrderAndMethodsAreRespected() {
        assertThat(access("GET /public/ping").kind()).isEqualTo(Access.Kind.PUBLIC);
        assertThat(access("DELETE /items/{id}").roles()).containsExactly("ADMIN");
        assertThat(access("GET /items/{id}").kind()).as("the DELETE rule does not cover GET").isEqualTo(Access.Kind.AUTHENTICATED);
        assertThat(access("GET /reports/daily").roles()).containsExactly("AUDITOR", "ADMIN");
        assertThat(access("GET /reports/daily").role()).isEqualTo("AUDITOR");
        assertThat(access("GET /other").kind()).as("anyRequest().authenticated()").isEqualTo(Access.Kind.AUTHENTICATED);
    }

    @Test
    void collectsEveryRoleTheProjectNames() {
        assertThat(model.roles(endpoints)).containsExactlyInAnyOrder("MANAGER", "SUPPORT", "ADMIN", "AUDITOR");
    }

    @Test
    void projectsWithoutSpringSecurityHaveNone(@TempDir Path other) throws IOException {
        Files.createDirectories(other.resolve("src/main/java"));
        assertThat(new SecurityScanner(s -> { }).scan(other, ProjectSettings.read(other)).style())
                .isEqualTo(SecurityModel.Style.NONE);
    }
}
