package com.springaimcpservercommon.loadtest.runtime;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@code GET /actuator/loadtest}: the routes this application serves right now as an OpenAPI 3 document, with the
 * extensions {@code x-loadtest-handler} (the Java method) and {@code x-loadtest-access} (who may call it, from method
 * security). Built per request, so routes registered at runtime are included.
 */
@Endpoint(id = "loadtest")
public class LoadTestEndpoint {

    private final LoadTestRuntimeProperties properties;
    private final String contextPath;
    private final String title;
    private final Supplier<List<RequestMappingHandlerMapping>> mappings;
    private final boolean enabled;

    LoadTestEndpoint(LoadTestRuntimeProperties properties, String contextPath, String title,
                     ObjectProvider<RequestMappingHandlerMapping> mappings) {
        this.properties = properties;
        this.contextPath = contextPath;
        this.title = title;
        this.mappings = () -> mappings.orderedStream().toList();
        this.enabled = true;
    }

    private LoadTestEndpoint() {
        this.properties = new LoadTestRuntimeProperties(false, false, null, null);
        this.contextPath = "";
        this.title = "";
        this.mappings = List::of;
        this.enabled = false;
    }

    static LoadTestEndpoint disabled() {
        return new LoadTestEndpoint();
    }

    /**
     * The model.
     *
     * @return an OpenAPI 3 document (empty when disabled for production)
     */
    @ReadOperation
    public Map<String, Object> model() {
        if (!enabled) {
            return Map.of("openapi", "3.0.3", "info", Map.of("title", "disabled", "version", "0"), "paths", Map.of());
        }
        return new RuntimeModelBuilder(properties).build(title, contextPath, mappings.get());
    }
}
