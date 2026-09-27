package com.springaimcpservercommon.core.catalog;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stable identifier of a catalog element (LLD-02 §3.3), stored in revisions, tool invocations and proposals.
 *
 * <p>Formats:
 * <ul>
 *   <li>{@code entity:com.acme.order.Order}</li>
 *   <li>{@code attr:com.acme.order.Order#status}</li>
 *   <li>{@code op:com.acme.order.OrderService#findRecentOrders(java.lang.Long,int)}</li>
 *   <li>{@code ctx:com.acme.order.OrderService} (descriptive context only)</li>
 *   <li>{@code query:<workspace>/<slug>}, {@code agent:<workspace>/<slug>}, {@code mcp:<server>/<tool>} (tool sources)</li>
 * </ul>
 *
 * @param kind  element kind
 * @param value the identifier after the {@code kind:} prefix
 */
public record CatalogElementRef(Kind kind, String value) {

    private static final Pattern VALUE = Pattern.compile("[A-Za-z0-9_$.#()\\[\\],/<>?-]{1,1000}");

    /** Element kinds; the lowercase name is the textual prefix. */
    public enum Kind {
        /** A JPA entity. */
        ENTITY,
        /** An attribute of an entity. */
        ATTR,
        /** A method on a Spring bean ({@code @AiExposedAction}). */
        OP,
        /** Descriptive context ({@code @AiContext} on a service or controller). */
        CTX,
        /** A published dynamic query used as a tool. */
        QUERY,
        /** An agent exposed as a tool. */
        AGENT,
        /** A tool of an external MCP server. */
        MCP;

        String prefix() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * Validates the components.
     *
     * @param kind  element kind
     * @param value identifier without prefix
     */
    public CatalogElementRef {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
        if (!VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid catalog element value: " + value);
        }
    }

    /**
     * Parses the textual form {@code kind:value}.
     *
     * @param text textual reference
     * @return the parsed reference
     * @throws IllegalArgumentException if the text is malformed
     */
    public static CatalogElementRef parse(String text) {
        int colon = text.indexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("catalog element ref must be kind:value — " + text);
        }
        Kind kind;
        try {
            kind = Kind.valueOf(text.substring(0, colon).toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown catalog element kind in " + text, e);
        }
        return new CatalogElementRef(kind, text.substring(colon + 1));
    }

    /**
     * Reference to an entity.
     *
     * @param entityClassName fully qualified class name
     * @return the reference
     */
    public static CatalogElementRef entity(String entityClassName) {
        return new CatalogElementRef(Kind.ENTITY, entityClassName);
    }

    /**
     * Reference to an operation (method) with its erased parameter types.
     *
     * @param beanType       declaring type (fully qualified)
     * @param method         method name
     * @param parameterTypes fully qualified parameter type names, in order
     * @return the reference
     */
    public static CatalogElementRef operation(String beanType, String method, java.util.List<String> parameterTypes) {
        return new CatalogElementRef(Kind.OP, beanType + "#" + method + "(" + String.join(",", parameterTypes) + ")");
    }

    @Override
    public String toString() {
        return kind.prefix() + ":" + value;
    }
}
