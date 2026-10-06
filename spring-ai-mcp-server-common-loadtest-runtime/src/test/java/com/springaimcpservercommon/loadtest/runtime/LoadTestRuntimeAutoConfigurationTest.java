package com.springaimcpservercommon.loadtest.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The endpoint only exists when asked for, and not in production unless that is allowed too. */
class LoadTestRuntimeAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LoadTestRuntimeAutoConfiguration.class))
            .withUserConfiguration(RuntimeModelBuilderTest.Config.class);

    @SuppressWarnings("unchecked")
    private static Map<String, Object> paths(LoadTestEndpoint endpoint) {
        return (Map<String, Object>) endpoint.model().get("paths");
    }

    @Test
    void offByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(LoadTestEndpoint.class));
    }

    @Test
    void onWhenEnabledAndAnswersWithTheLiveRoutes() {
        runner.withPropertyValues("loadtest.runtime.enabled=true", "server.servlet.context-path=/shop",
                "spring.application.name=shop").run(ctx -> {
            assertThat(ctx).hasSingleBean(LoadTestEndpoint.class);
            Map<String, Object> model = ctx.getBean(LoadTestEndpoint.class).model();
            assertThat(model.get("info")).isEqualTo(Map.of("title", "shop", "version", "runtime"));
            assertThat(model.get("servers").toString()).contains("/shop");
            assertThat(((Map<?, ?>) model.get("paths")).keySet().stream().map(String::valueOf).toList())
                    .contains("/api/orders", "/api/orders/{id}");
        });
    }

    @Test
    void aProductionProfileKeepsItEmptyUnlessAllowed() {
        runner.withPropertyValues("loadtest.runtime.enabled=true", "spring.profiles.active=production").run(ctx -> {
            assertThat(((Map<?, ?>) ctx.getBean(LoadTestEndpoint.class).model().get("paths"))).isEmpty();
        });
        runner.withPropertyValues("loadtest.runtime.enabled=true", "spring.profiles.active=production",
                "loadtest.runtime.allow-production=true").run(ctx -> {
            assertThat(((Map<?, ?>) ctx.getBean(LoadTestEndpoint.class).model().get("paths"))).isNotEmpty();
        });
    }

    @Test
    void routesRegisteredAtRuntimeAreIncludedBecauseTheModelIsBuiltPerRequest() {
        runner.withPropertyValues("loadtest.runtime.enabled=true").run(ctx -> {
            LoadTestEndpoint endpoint = ctx.getBean(LoadTestEndpoint.class);
            assertThat(paths(endpoint)).doesNotContainKey("/runtime/added");
            RequestMappingHandlerMapping mapping = ctx.getBean(RequestMappingHandlerMapping.class);
            mapping.registerMapping(RequestMappingInfo.paths("/runtime/added").methods(RequestMethod.GET).build(),
                    ctx.getBean(RuntimeModelBuilderTest.OrderController.class),
                    RuntimeModelBuilderTest.OrderController.class.getDeclaredMethod("open"));
            assertThat(paths(endpoint)).containsKey("/runtime/added");
        });
    }
}
