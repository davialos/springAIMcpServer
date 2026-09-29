package com.springaimcpservercommon.mcp.server;

import com.springaimcpservercommon.core.json.CanonicalJson;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.security.mcp.McpClientApproval;
import com.springaimcpservercommon.security.web.WwwAuthenticateHeaders;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The MCP endpoint (F-55, LLD-07 §5, ADR-0016): Streamable HTTP, stateless, {@code POST /dynamic-ai/mcp}.
 *
 * <p>Order of checks, each failing closed: {@code Origin} (403) → {@code MCP-Protocol-Version} (400) → body size cap
 * (413) → authentication (401 with {@code WWW-Authenticate: Bearer resource_metadata=…}) → workspace (400) →
 * approved MCP client (403) → {@link McpProtocolHandler}. Authentication itself is the host's Spring Security filter
 * chain (resource server or API key); this controller only reads the resulting authentication and never accepts
 * credentials of its own. The MCP token is never forwarded anywhere (no token passthrough).
 *
 * <p>The workspace is the {@value #WORKSPACE_HEADER} header, or the configured default; without either the request
 * is refused. {@code GET} and {@code DELETE} answer 405 (no server-initiated stream, no sessions). The RFC 9728
 * protected-resource metadata is public and served next to the endpoint and at the well-known path.
 *
 * <p>Not a {@code @Component}; registered by {@code DaiMcpAutoConfiguration}.
 */
@NullMarked
@RequestMapping
public final class McpEndpointController {

    /** Header naming the workspace a request addresses. */
    public static final String WORKSPACE_HEADER = "X-DAI-Workspace";

    /** Path of the endpoint. */
    public static final String PATH = "/dynamic-ai/mcp";

    private static final Logger LOG = LoggerFactory.getLogger(McpEndpointController.class);
    private static final String PROTOCOL_HEADER = "MCP-Protocol-Version";
    private static final String DEFAULT_PROTOCOL = "2025-03-26";

    /**
     * Endpoint settings.
     *
     * @param defaultWorkspaceId workspace used when the request names none, or {@code null} to require the header
     * @param maxRequestBytes    largest accepted request body
     * @param resourceUri        absolute URI of this MCP resource (audience), or {@code null} to derive it from the request
     */
    public record Settings(@Nullable UUID defaultWorkspaceId, int maxRequestBytes, @Nullable String resourceUri) {
        /** Validates the settings. */
        public Settings {
            if (maxRequestBytes < 1024) {
                throw new IllegalArgumentException("maxRequestBytes must be at least 1024");
            }
        }
    }

    /** Port: resolves the authenticated caller of a request. */
    @FunctionalInterface
    public interface PrincipalResolver {
        /**
         * @param request current request
         * @return the caller
         * @throws SecurityException if the request is not authenticated
         */
        DaiPrincipal resolve(HttpServletRequest request);
    }

    private final McpProtocolHandler handler;
    private final PrincipalResolver principals;
    private final McpOriginValidator origins;
    private final @Nullable McpClientApproval clientApproval;
    private final ProtectedResourceMetadata metadata;
    private final Settings settings;

    /**
     * Creates the controller.
     *
     * @param handler        protocol handler
     * @param principals     resolves the caller
     * @param origins        DNS-rebinding protection
     * @param clientApproval approved-client check, or {@code null} to skip it (not recommended)
     * @param metadata       RFC 9728 metadata to publish
     * @param settings       endpoint settings
     */
    public McpEndpointController(McpProtocolHandler handler, PrincipalResolver principals,
                                 McpOriginValidator origins, @Nullable McpClientApproval clientApproval,
                                 ProtectedResourceMetadata metadata, Settings settings) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.principals = Objects.requireNonNull(principals, "principals");
        this.origins = Objects.requireNonNull(origins, "origins");
        this.clientApproval = clientApproval;
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * Handles one JSON-RPC message.
     *
     * @param request current request
     * @return the JSON-RPC response, 202 for a notification, or an error status
     */
    @PostMapping(PATH)
    public ResponseEntity<String> post(HttpServletRequest request) {
        if (!origins.isAllowed(request.getHeader(HttpHeaders.ORIGIN))) {
            return rpcError(HttpStatus.FORBIDDEN, McpProtocolHandler.INVALID_REQUEST, "Origin not allowed");
        }
        String version = request.getHeader(PROTOCOL_HEADER);
        if (version != null && !McpProtocolHandler.SUPPORTED_VERSIONS.contains(version)
                && !DEFAULT_PROTOCOL.equals(version)) {
            return rpcError(HttpStatus.BAD_REQUEST, McpProtocolHandler.INVALID_REQUEST,
                    "Unsupported MCP-Protocol-Version");
        }
        if (request.getContentLengthLong() > settings.maxRequestBytes()) {
            return tooLarge();
        }
        DaiPrincipal principal;
        Authentication authentication;
        try {
            principal = principals.resolve(request);
            authentication = Objects.requireNonNull(SecurityContextHolder.getContext().getAuthentication());
        } catch (RuntimeException e) {
            return unauthorized(request);
        }
        UUID workspaceId;
        try {
            workspaceId = workspace(request);
        } catch (IllegalArgumentException e) {
            return rpcError(HttpStatus.BAD_REQUEST, McpProtocolHandler.INVALID_REQUEST,
                    "Set the " + WORKSPACE_HEADER + " header to a workspace id");
        }
        UUID mcpClientId = null;
        if (clientApproval != null) {
            McpClientApproval.Result admitted = clientApproval.admit(authentication, principal, workspaceId);
            if (admitted instanceof McpClientApproval.NotApproved) {
                return rpcError(HttpStatus.FORBIDDEN, McpProtocolHandler.INVALID_REQUEST, "MCP client not approved");
            }
            if (admitted instanceof McpClientApproval.Approved approved) {
                mcpClientId = approved.mcpClientId();
            }
        }
        String body;
        try {
            byte[] bytes = request.getInputStream().readNBytes(settings.maxRequestBytes() + 1);
            if (bytes.length > settings.maxRequestBytes()) {
                return tooLarge();
            }
            body = new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.debug("MCP request body could not be read ({})", e.getClass().getSimpleName());
            return rpcError(HttpStatus.BAD_REQUEST, McpProtocolHandler.PARSE_ERROR, "Parse error");
        }
        McpProtocolHandler.Reply reply = handler.handle(body,
                new McpProtocolHandler.Caller(principal, authentication, workspaceId, mcpClientId));
        ResponseEntity.BodyBuilder response = ResponseEntity.status(reply.httpStatus());
        if (reply.body() == null) {
            return response.build();
        }
        return response.contentType(MediaType.APPLICATION_JSON).body(reply.body());
    }

    /**
     * No server-initiated stream in stateless mode.
     *
     * @return 405
     */
    @GetMapping(PATH)
    public ResponseEntity<String> get() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "POST").build();
    }

    /**
     * Sessions do not exist in stateless mode, so there is nothing to terminate.
     *
     * @return 405
     */
    @org.springframework.web.bind.annotation.DeleteMapping(PATH)
    public ResponseEntity<String> delete() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "POST").build();
    }

    /**
     * RFC 9728 protected-resource metadata (public).
     *
     * @return the metadata document
     */
    @GetMapping({"/.well-known/oauth-protected-resource" + PATH, PATH + "/.well-known/oauth-protected-resource"})
    public ResponseEntity<String> protectedResource() {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(metadata.toJson());
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────────────────

    private UUID workspace(HttpServletRequest request) {
        String header = request.getHeader(WORKSPACE_HEADER);
        if (header != null && !header.isBlank()) {
            return UUID.fromString(header.strip());
        }
        UUID fallback = settings.defaultWorkspaceId();
        if (fallback == null) {
            throw new IllegalArgumentException("no workspace");
        }
        return fallback;
    }

    private ResponseEntity<String> unauthorized(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE,
                        WwwAuthenticateHeaders.bearer().resourceMetadata(metadataUrl(request)).build())
                .contentType(MediaType.APPLICATION_JSON)
                .body(rpcErrorBody(McpProtocolHandler.INVALID_REQUEST, "Authentication required"));
    }

    private String metadataUrl(HttpServletRequest request) {
        String origin;
        String resource = settings.resourceUri();
        if (resource != null && resource.startsWith("http")) {
            URI uri = URI.create(resource);
            origin = uri.getScheme() + "://" + uri.getRawAuthority();
        } else {
            int port = request.getServerPort();
            boolean standard = port == 80 || port == 443;
            origin = request.getScheme() + "://" + request.getServerName() + (standard ? "" : ":" + port);
        }
        return origin + "/.well-known/oauth-protected-resource" + PATH;
    }

    private static ResponseEntity<String> tooLarge() {
        return ResponseEntity.status(413).contentType(MediaType.APPLICATION_JSON)
                .body(rpcErrorBody(McpProtocolHandler.INVALID_REQUEST, "Request too large"));
    }

    private static ResponseEntity<String> rpcError(HttpStatus status, int code, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(rpcErrorBody(code, message));
    }

    private static String rpcErrorBody(int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", null);
        response.put("error", Map.of("code", code, "message", message));
        return CanonicalJson.write(response);
    }
}
