package com.springaimcpservercommon.webmvc.endpoint;

import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * An immutable, published dynamic endpoint definition (LLD-04 §2).
 *
 * <p>Full URL = {@code {base-path}/api/{workspaceSlug}/{apiVersion}{path}}
 * where {@code {base-path}} is {@code /dynamic-ai}. The workspace prefix prevents
 * cross-workspace path collisions.
 *
 * @param id            unique endpoint id (UUIDv7)
 * @param revision      monotonically increasing revision
 * @param workspaceId   owning workspace
 * @param workspaceSlug workspace URL slug (used in the full path)
 * @param path          relative path fragment, starting with {@code /}, e.g. {@code /orders/{orderId}}
 * @param method        HTTP method
 * @param apiVersion    version string, e.g. {@code v1}
 * @param params        parameter declarations (in order)
 * @param requestBodySchema JSON Schema for POST body; {@code null} for GET
 * @param backing       execution backing
 * @param response      response shaping
 * @param rateLimit     per-principal and workspace rate limits
 * @param cache         response caching config
 * @param timeout       execution timeout
 * @param references    catalog elements referenced by this endpoint (for drift detection)
 * @param catalogHash   effective catalog fingerprint validated at publish time
 */
public record EndpointDefinition(
        UUID id,
        int revision,
        UUID workspaceId,
        String workspaceSlug,
        String path,
        DaiHttpMethod method,
        String apiVersion,
        List<ParamSpec> params,
        @Nullable String requestBodySchema,
        Backing backing,
        ResponseShape response,
        RateLimitSpec rateLimit,
        CacheSpec cache,
        Duration timeout,
        Set<CatalogElementRef> references,
        String catalogHash) {

    private static final Pattern API_VERSION = Pattern.compile("^v[1-9][0-9]*$");
    private static final Pattern PATH_PATTERN = Pattern.compile("^/[a-zA-Z0-9_.{}/-]*$");
    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{1,63}$");

    /** Validates and defensively copies mutable collections. */
    public EndpointDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(workspaceSlug, "workspaceSlug");
        if (!SLUG_PATTERN.matcher(workspaceSlug).matches()) {
            throw new IllegalArgumentException("workspaceSlug must match ^[a-z][a-z0-9-]{1,63}$: " + workspaceSlug);
        }
        Objects.requireNonNull(path, "path");
        if (!PATH_PATTERN.matcher(path).matches()) {
            throw new IllegalArgumentException("path must start with / and contain only valid URI chars: " + path);
        }
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(apiVersion, "apiVersion");
        if (!API_VERSION.matcher(apiVersion).matches()) {
            throw new IllegalArgumentException("apiVersion must match ^v[1-9][0-9]*$: " + apiVersion);
        }
        Objects.requireNonNull(backing, "backing");
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(rateLimit, "rateLimit");
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(catalogHash, "catalogHash");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        params = List.copyOf(params);
        references = Set.copyOf(references);
    }

    /**
     * The full path fragment registered in Spring MVC, relative to the host's context path.
     * Format: {@code /dynamic-ai/api/{workspaceSlug}/{apiVersion}{path}}.
     *
     * @return full MVC path
     */
    public String fullPath() {
        return "/dynamic-ai/api/" + workspaceSlug + "/" + apiVersion + path;
    }
}
