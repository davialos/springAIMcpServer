package com.springaimcpservercommon.webmvc.endpoint;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the lifecycle of dynamic Spring MVC routes for published {@link EndpointDefinition}s (LLD-04 §3).
 *
 * <p>On a catalog snapshot change ({@link #applySnapshot}):
 * <ol>
 *   <li>Diffs the current route table against the new generation.</li>
 *   <li>Validates new routes (pattern parse, collision check).</li>
 *   <li>Registers new routes with Spring MVC's {@link RequestMappingHandlerMapping}.</li>
 *   <li>Removes obsolete routes.</li>
 *   <li>Atomically swaps the route table (most publishes are a pure map swap).</li>
 * </ol>
 *
 * <p>A {@link RegistrationFailure} is recorded for any route that fails — other routes in the
 * same generation are still applied and the generation is marked {@code PARTIAL}.
 *
 * <p>Thread safety: {@link #applySnapshot} serialises via {@code synchronized}; reads of the
 * route table via {@link #routeTable} are lock-free (AtomicReference).
 */
@NullMarked
public final class DynamicEndpointRegistrar implements EndpointLookup {

    private static final Logger LOG = LoggerFactory.getLogger(DynamicEndpointRegistrar.class);

    /** Recorded when a single route cannot be registered. */
    public record RegistrationFailure(RouteKey key, String reason, @Nullable Exception cause) {}

    private final RequestMappingHandlerMapping handlerMapping;
    private final Object handlerObject;
    private final Method handlerMethod;
    private final AtomicReference<Map<RouteKey, EndpointDefinition>> routeTable =
            new AtomicReference<>(Map.of());

    /**
     * Creates the registrar.
     *
     * @param handlerMapping the host's primary {@link RequestMappingHandlerMapping}
     * @param handlerObject  the handler object (always {@link GenericDynamicHandler})
     * @param handlerMethod  the handler method to invoke ({@code GenericDynamicHandler#handle})
     */
    public DynamicEndpointRegistrar(RequestMappingHandlerMapping handlerMapping,
                                     Object handlerObject, Method handlerMethod) {
        this.handlerMapping = Objects.requireNonNull(handlerMapping, "handlerMapping");
        this.handlerObject = Objects.requireNonNull(handlerObject, "handlerObject");
        this.handlerMethod = Objects.requireNonNull(handlerMethod, "handlerMethod");
    }

    /**
     * Applies a new generation of endpoint definitions.
     *
     * <p>Called by the autoconfigure module's snapshot applier when the effective catalog
     * or endpoint configuration changes.
     *
     * @param newDefinitions the complete set of published endpoint definitions for this node
     * @return list of failures (empty if all routes applied cleanly)
     */
    public synchronized List<RegistrationFailure> applySnapshot(Collection<EndpointDefinition> newDefinitions) {
        Objects.requireNonNull(newDefinitions, "newDefinitions");
        Map<RouteKey, EndpointDefinition> current = routeTable.get();
        Map<RouteKey, EndpointDefinition> next = new LinkedHashMap<>();
        for (EndpointDefinition def : newDefinitions) {
            next.put(RouteKey.of(def), def);
        }

        List<RegistrationFailure> failures = new java.util.ArrayList<>();

        // Register new or replaced routes
        for (var entry : next.entrySet()) {
            RouteKey key = entry.getKey();
            EndpointDefinition newDef = entry.getValue();
            EndpointDefinition existing = current.get(key);
            if (existing != null && existing.id().equals(newDef.id())
                    && existing.revision() == newDef.revision()) {
                continue; // same revision — no change
            }
            if (existing != null) {
                // Path/method unchanged, revision changed — pure route-table swap (no re-registration needed)
                LOG.debug("Updating endpoint {} rev {} (no re-registration needed)", key, newDef.revision());
            } else {
                RegistrationFailure failure = registerRoute(key, newDef);
                if (failure != null) {
                    failures.add(failure);
                    next.remove(key);
                }
            }
        }

        // Unregister removed routes
        for (RouteKey key : current.keySet()) {
            if (!next.containsKey(key)) {
                unregisterRoute(key, current.get(key));
            }
        }

        routeTable.set(Collections.unmodifiableMap(next));
        LOG.info("Endpoint snapshot applied: {} routes active, {} failures", next.size(), failures.size());
        return List.copyOf(failures);
    }

    /**
     * Returns the current route table (live, lock-free read).
     *
     * @return immutable snapshot of the route table
     */
    public Map<RouteKey, EndpointDefinition> routeTable() {
        return routeTable.get();
    }

    /**
     * Looks up the endpoint definition for a request by its full path and method.
     *
     * @param fullPath full path (e.g. from {@code HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE})
     * @param method   HTTP method string
     * @return the endpoint definition, or {@code null} if not found
     */
    @Override
    public @Nullable EndpointDefinition lookup(String fullPath, String method) {
        DaiHttpMethod daiMethod;
        try {
            daiMethod = DaiHttpMethod.valueOf(method.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
        return routeTable.get().get(new RouteKey(daiMethod, fullPath));
    }

    private @Nullable RegistrationFailure registerRoute(RouteKey key, EndpointDefinition def) {
        try {
            var config = handlerMapping.getBuilderConfiguration();
            RequestMappingInfo info = RequestMappingInfo
                    .paths(def.fullPath())
                    .methods(toSpringMethod(key.method()))
                    .produces("application/json")
                    .options(config)
                    .build();
            handlerMapping.registerMapping(info, handlerObject, handlerMethod);
            LOG.info("Registered dynamic endpoint {} {}", key.method(), def.fullPath());
            return null;
        } catch (Exception e) {
            LOG.error("Failed to register dynamic endpoint {} {}: {}", key.method(), def.fullPath(), e.getMessage());
            return new RegistrationFailure(key, e.getMessage() != null ? e.getMessage() : "unknown", e);
        }
    }

    private void unregisterRoute(RouteKey key, EndpointDefinition def) {
        try {
            var config = handlerMapping.getBuilderConfiguration();
            RequestMappingInfo info = RequestMappingInfo
                    .paths(def.fullPath())
                    .methods(toSpringMethod(key.method()))
                    .produces("application/json")
                    .options(config)
                    .build();
            handlerMapping.unregisterMapping(info);
            LOG.info("Unregistered dynamic endpoint {} {}", key.method(), def.fullPath());
        } catch (Exception e) {
            LOG.warn("Failed to unregister dynamic endpoint {} {}: {}", key.method(), def.fullPath(), e.getMessage());
        }
    }

    private static org.springframework.web.bind.annotation.RequestMethod toSpringMethod(DaiHttpMethod method) {
        return switch (method) {
            case GET -> org.springframework.web.bind.annotation.RequestMethod.GET;
            case POST -> org.springframework.web.bind.annotation.RequestMethod.POST;
        };
    }
}
