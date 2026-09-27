package com.springaimcpservercommon.security.authz;

import com.springaimcpservercommon.annotations.Classification;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * The resource an authorization decision is about.
 *
 * @param workspaceId    owning workspace
 * @param resourceId     {@code dai_resource.id}
 * @param kind           resource kind ({@code ENDPOINT}, {@code QUERY}, {@code AGENT}, {@code TOOL_BINDING}, …), used by
 *                       grant patterns
 * @param slug           resource slug, used by grant patterns
 * @param classification highest data classification the invocation can touch (SEC-01 §7 step 5)
 */
public record ResourceRef(UUID workspaceId, UUID resourceId, String kind, String slug, Classification classification) {

    /**
     * Validates components.
     */
    public ResourceRef {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(classification, "classification");
        if (classification == Classification.INHERIT) {
            throw new IllegalArgumentException("resource classification must be effective, not INHERIT");
        }
    }

    /**
     * The string grant patterns are matched against: {@code <kind lower-case>/<slug>}, e.g. {@code query/orders-by-customer}.
     *
     * @return the pattern subject
     */
    public String patternKey() {
        return kind.toLowerCase(Locale.ROOT) + "/" + slug;
    }

    /**
     * Convenience for callers that only know the id (patterns then never match).
     *
     * @param workspaceId    workspace
     * @param resourceId     resource
     * @param classification effective classification
     * @return the reference
     */
    public static ResourceRef of(UUID workspaceId, UUID resourceId, Classification classification) {
        return new ResourceRef(workspaceId, resourceId, "unknown", resourceId.toString(), classification);
    }
}
