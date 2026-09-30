package com.springaimcpservercommon.webmvc.endpoint;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Finds the dynamic endpoint behind a matched route. Implemented by {@link DynamicEndpointRegistrar}; the
 * {@link GenericDynamicHandler} depends on this port rather than on the registrar, because the registrar in turn
 * needs the handler to register routes (the dependency would otherwise be circular).
 */
@NullMarked
@FunctionalInterface
public interface EndpointLookup {

    /**
     * Returns the endpoint registered for a route.
     *
     * @param fullPath the matched route pattern
     * @param method   the HTTP method
     * @return the endpoint, or {@code null} when none is registered
     */
    @Nullable EndpointDefinition lookup(String fullPath, String method);
}
