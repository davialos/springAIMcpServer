package com.springaimcpservercommon.loadtest.model;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One discovered REST operation.
 *
 * @param id       stable identifier (OpenAPI {@code operationId} or controller method name), a valid JS identifier
 * @param method   HTTP method
 * @param path     URI template relative to the base URL, e.g. {@code /api/users/{id}}
 * @param summary  human description, if known
 * @param tags     grouping tags (controller name or OpenAPI tags)
 * @param params   path, query and header parameters
 * @param body     JSON request body schema, or {@code null}
 * @param resource entity the endpoint operates on, when known (e.g. the controller's {@code @AiContext} or the
 *                 collection segment of the path); used to bind real data
 * @param sources  where the endpoint was discovered ({@code source}, {@code openapi}, {@code actuator})
 */
public record ApiEndpoint(String id, HttpMethod method, String path, @Nullable String summary, List<String> tags,
                          List<ApiParam> params, @Nullable Schema body, @Nullable String resource,
                          Set<String> sources) {

    /** Compact constructor: defensive copies. */
    public ApiEndpoint {
        tags = List.copyOf(tags);
        params = List.copyOf(params);
        sources = Set.copyOf(new LinkedHashSet<>(sources));
    }

    /**
     * Method-and-path key under which endpoints from different sources are merged: path variables are
     * anonymised, so {@code /users/{id}} and {@code /users/{userId}} are the same operation.
     *
     * @return merge key
     */
    public String routeKey() {
        return routeKey(method, path);
    }

    /**
     * Merge key for a method and path.
     *
     * @param method HTTP method
     * @param path   URI template
     * @return merge key
     */
    public static String routeKey(HttpMethod method, String path) {
        String normalized = path.replaceAll("\\{[^}]*}", "{}").replaceAll("/+$", "");
        return method + " " + (normalized.isEmpty() ? "/" : normalized.toLowerCase(Locale.ROOT));
    }

    /**
     * The k6 request name tag: method and template, so every call of the operation groups into one series.
     *
     * @return tag value
     */
    public String displayName() {
        return method + " " + path;
    }

    /**
     * Parameters at one location.
     *
     * @param location location
     * @return matching parameters, in declaration order
     */
    public List<ApiParam> params(ParamLocation location) {
        List<ApiParam> result = new ArrayList<>();
        for (ApiParam p : params) {
            if (p.in() == location) {
                result.add(p);
            }
        }
        return result;
    }

    /**
     * Copy with another id.
     *
     * @param newId id
     * @return the copy
     */
    public ApiEndpoint withId(String newId) {
        return new ApiEndpoint(newId, method, path, summary, tags, params, body, resource, sources);
    }
}
