package com.springaimcpservercommon.webmvc.endpoint;

import java.util.Objects;

/**
 * Identifies a registered route by HTTP method and normalized full path (LLD-04 §3).
 *
 * <p>Used as the key in the route table ({@code Map<RouteKey, EndpointDefinition>}) and
 * for collision detection between dynamic routes and host routes.
 *
 * @param method      HTTP method
 * @param fullPath    full path as registered (e.g. {@code /dynamic-ai/api/sales/v1/orders/{orderId}})
 */
public record RouteKey(DaiHttpMethod method, String fullPath) {

    /** Validates. */
    public RouteKey {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(fullPath, "fullPath");
    }

    /**
     * Builds the route key for a published endpoint definition.
     *
     * @param def the endpoint definition
     * @return the key
     */
    public static RouteKey of(EndpointDefinition def) {
        return new RouteKey(def.method(), def.fullPath());
    }
}
