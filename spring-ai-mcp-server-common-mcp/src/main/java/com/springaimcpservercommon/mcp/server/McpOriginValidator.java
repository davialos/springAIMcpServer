package com.springaimcpservercommon.mcp.server;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * DNS-rebinding protection for the MCP endpoint (LLD-07 §5.5).
 *
 * <p>Validates that the {@code Origin} header of an HTTP request matches one of the configured
 * allowed origins. An absent {@code Origin} header is allowed (server-to-server API-key requests
 * and same-origin browser requests both omit it). An {@code Origin} that does not match any
 * allowed origin is rejected.
 *
 * <p>The {@code allowedOrigins} list is configured at startup by the admin. The host origin
 * (scheme + host + port) is always implicitly allowed; additional MCP client origins must be
 * explicitly listed.
 */
public final class McpOriginValidator {

    private static final Logger LOG = LoggerFactory.getLogger(McpOriginValidator.class);

    private final List<String> allowedOrigins;

    /**
     * Creates the validator.
     *
     * @param allowedOrigins allowed origin strings (scheme + host + optional port),
     *                       e.g. {@code https://mcp-client.example.com}. The list is copied.
     */
    public McpOriginValidator(Collection<String> allowedOrigins) {
        this.allowedOrigins = List.copyOf(Objects.requireNonNull(allowedOrigins, "allowedOrigins"));
    }

    /**
     * @return {@code true} if the request should be allowed
     */
    public boolean isAllowed(@Nullable String originHeader) {
        if (originHeader == null || originHeader.isBlank()) {
            return true;
        }
        String normalised = normalise(originHeader);
        if (normalised == null) {
            LOG.warn("MCP: malformed Origin header '{}'; rejecting", originHeader);
            return false;
        }
        for (String allowed : allowedOrigins) {
            if (normalised.equalsIgnoreCase(allowed)) {
                return true;
            }
        }
        LOG.info("MCP: Origin '{}' not in allowed list; rejecting", normalised);
        return false;
    }

    @Nullable
    private static String normalise(String origin) {
        try {
            URI uri = new URI(origin.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            if (scheme == null || host == null) return null;
            if (port == -1) return scheme + "://" + host;
            return scheme + "://" + host + ":" + port;
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
