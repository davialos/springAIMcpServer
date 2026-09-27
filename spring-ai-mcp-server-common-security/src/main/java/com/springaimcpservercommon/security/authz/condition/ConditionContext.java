package com.springaimcpservercommon.security.authz.condition;

import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.security.authz.ResourceRef;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Facts an ABAC condition can reference. Attribute paths are {@code <namespace>.<name>}:
 * <ul>
 *   <li>{@code principal.*} — built-ins {@code type}, {@code issuer}, {@code subjectId}, {@code clearance}
 *       (built-ins win over same-named attributes), then {@link DaiPrincipal#attributes()};</li>
 *   <li>{@code environment.*} — facts configured by the host at startup (e.g. {@code tier}, {@code environmentId});</li>
 *   <li>{@code request.*} — trusted request facts supplied by our adapters (never model- or client-supplied);</li>
 *   <li>{@code resource.*} — {@code kind}, {@code slug}, {@code classification}, {@code workspaceId},
 *       {@code resourceId}.</li>
 * </ul>
 *
 * @param principal   caller
 * @param environment environment facts
 * @param request     request facts
 * @param resource    resource, if any
 * @param now         evaluation time
 */
public record ConditionContext(DaiPrincipal principal, Map<String, Object> environment, Map<String, Object> request,
                               @Nullable ResourceRef resource, Instant now) {

    /**
     * Validates and copies.
     */
    public ConditionContext {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(now, "now");
        environment = Collections.unmodifiableMap(new LinkedHashMap<>(environment));
        request = Collections.unmodifiableMap(new LinkedHashMap<>(request));
    }

    /**
     * Resolves an attribute path. Enum and UUID values are rendered as strings.
     *
     * @param path {@code namespace.name}
     * @return the value, empty if absent or the namespace is unknown
     */
    public Optional<Object> resolve(String path) {
        int dot = path.indexOf('.');
        if (dot <= 0) {
            return Optional.empty();
        }
        String namespace = path.substring(0, dot);
        String name = path.substring(dot + 1);
        Object value = switch (namespace) {
            case "principal" -> principalValue(name);
            case "environment" -> environment.get(name);
            case "request" -> request.get(name);
            case "resource" -> resourceValue(name);
            default -> null;
        };
        return Optional.ofNullable(normalize(value));
    }

    private @Nullable Object principalValue(String name) {
        return switch (name) {
            case "type" -> principal.type();
            case "issuer" -> principal.issuer();
            case "subjectId" -> principal.subjectId();
            case "clearance" -> principal.clearance();
            default -> principal.attributes().get(name);
        };
    }

    private @Nullable Object resourceValue(String name) {
        if (resource == null) {
            return null;
        }
        return switch (name) {
            case "kind" -> resource.kind();
            case "slug" -> resource.slug();
            case "classification" -> resource.classification();
            case "workspaceId" -> resource.workspaceId();
            case "resourceId" -> resource.resourceId();
            default -> null;
        };
    }

    private static @Nullable Object normalize(@Nullable Object value) {
        return switch (value) {
            case null -> null;
            case Enum<?> e -> e.name();
            case java.util.UUID u -> u.toString();
            default -> value;
        };
    }
}
