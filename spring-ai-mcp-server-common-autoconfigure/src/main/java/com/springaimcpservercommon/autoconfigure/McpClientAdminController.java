package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.persistence.audit.AuditCategory;
import com.springaimcpservercommon.persistence.audit.AuditPlane;
import com.springaimcpservercommon.persistence.identity.McpClientStatus;
import com.springaimcpservercommon.persistence.identity.McpClientStore;
import com.springaimcpservercommon.persistence.identity.McpClientView;
import com.springaimcpservercommon.persistence.identity.McpRegistrationType;
import com.springaimcpservercommon.security.permission.Permission;
import com.springaimcpservercommon.webmvc.problem.ProblemCode;
import com.springaimcpservercommon.webmvc.problem.ProblemDetailFactory.FieldViolation;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * MCP client admin API (LLD-07 §5.3, F-55): registers, approves and revokes the OAuth clients allowed to use the
 * workspace's MCP endpoint. Until a client is {@code APPROVED} the endpoint refuses its tokens (default deny).
 * Requires {@link Permission#SERVICEACCOUNT_MANAGE} (clients are machine identities); the person who registered a
 * client cannot approve it (segregation of duties). Every change is audited.
 *
 * <p>Not a {@code @Component}; registered by {@link DaiAdminAutoConfiguration}.
 */
@NullMarked
@RequestMapping("/dynamic-ai/admin/api/v1/workspaces/{workspaceId}/mcp-clients")
public final class McpClientAdminController {

    static final int MAX_TEXT = 512;

    /**
     * Register request.
     *
     * @param issuer           issuer of the authorization server that knows the client (the token's {@code iss})
     * @param clientId         OAuth client id as it appears in the token ({@code azp}/{@code client_id})
     * @param displayName      name shown to people
     * @param registrationType {@code PRE_REGISTERED} (default), {@code CIMD} or {@code DCR}
     */
    public record RegisterRequest(@Nullable String issuer, @Nullable String clientId, @Nullable String displayName,
                                  @Nullable String registrationType) {}

    /**
     * A client as shown to the UI.
     *
     * @param id               registration id
     * @param issuer           issuer
     * @param clientId         client id
     * @param displayName      display name
     * @param registrationType registration type
     * @param status           PENDING, APPROVED or REVOKED
     * @param approvedBy       approver, if approved
     * @param approvedAt       approval time, if approved
     * @param createdAt        registration time
     */
    public record ClientDto(UUID id, String issuer, String clientId, String displayName, String registrationType,
                            String status, @Nullable UUID approvedBy, @Nullable Instant approvedAt,
                            Instant createdAt) {
        static ClientDto of(McpClientView v) {
            return new ClientDto(v.id(), v.issuer(), v.clientId(), v.displayName(), v.registrationType().name(),
                    v.status().name(), v.approvedBy(), v.approvedAt(), v.createdAt());
        }
    }

    private final McpClientStore store;
    private final AdminAudit audit;
    private final AdminApi api;

    McpClientAdminController(McpClientStore store, AdminAudit audit, AdminApi api) {
        this.store = Objects.requireNonNull(store, "store");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.api = Objects.requireNonNull(api, "api");
    }

    /**
     * Lists the clients registered for a workspace.
     *
     * @param workspaceId workspace
     * @param request     current request
     * @return 200 with the clients
     */
    @GetMapping
    public ResponseEntity<?> list(@PathVariable UUID workspaceId, HttpServletRequest request) {
        var gate = api.gate(request, Permission.SERVICEACCOUNT_MANAGE, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        return ResponseEntity.ok(store.listByWorkspace(workspaceId).stream().map(ClientDto::of).toList());
    }

    /**
     * Registers a client in PENDING state; it is served only after {@code :approve}.
     *
     * @param workspaceId workspace
     * @param body        issuer, client id, name
     * @param request     current request
     * @return 201 with the client; 400 with field errors; 409 when already registered
     */
    @PostMapping
    public ResponseEntity<?> register(@PathVariable UUID workspaceId, @RequestBody RegisterRequest body,
                                      HttpServletRequest request) {
        var gate = api.gate(request, Permission.SERVICEACCOUNT_MANAGE, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        List<FieldViolation> errors = new ArrayList<>();
        String issuer = AdminApi.text(errors, "issuer", body.issuer(), true, MAX_TEXT);
        String clientId = AdminApi.text(errors, "clientId", body.clientId(), true, MAX_TEXT);
        String name = AdminApi.text(errors, "displayName", body.displayName(), true, MAX_TEXT);
        McpRegistrationType type = McpRegistrationType.PRE_REGISTERED;
        if (body.registrationType() != null && !body.registrationType().isBlank()) {
            try {
                type = McpRegistrationType.valueOf(body.registrationType().strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                errors.add(new FieldViolation("registrationType", "must be PRE_REGISTERED, CIMD or DCR"));
            }
        }
        if (!errors.isEmpty()) {
            return AdminApi.validation(request, errors);
        }
        if (store.listByWorkspace(workspaceId).stream()
                .anyMatch(c -> c.issuer().equals(issuer) && c.clientId().equals(clientId))) {
            return AdminApi.problem(ProblemCode.CONFLICT, "Already registered", null, request);
        }
        DaiPrincipal caller = gate.caller();
        McpClientView created = store.register(workspaceId, Objects.requireNonNull(issuer),
                Objects.requireNonNull(clientId), Objects.requireNonNull(name), type, caller.principalId());
        audit.record(caller, AuditCategory.ADMIN, AuditPlane.CONTROL, "MCP_CLIENT_REGISTERED", workspaceId,
                "mcp_client", created.id().toString(), null, null, Map.of("clientId", clientId));
        return ResponseEntity.status(HttpStatus.CREATED).body(ClientDto.of(created));
    }

    /**
     * Approves a pending client. Not the person who registered it.
     *
     * @param workspaceId workspace
     * @param id          client registration
     * @param request     current request
     * @return 200 with the client; 403 for the registrar; 404 unknown; 409 unless PENDING
     */
    @PostMapping("/{id:[^:]+}:approve")
    public ResponseEntity<?> approve(@PathVariable UUID workspaceId, @PathVariable UUID id,
                                     HttpServletRequest request) {
        var gate = api.gate(request, Permission.SERVICEACCOUNT_MANAGE, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        McpClientView client = store.find(id).filter(c -> c.workspaceId().equals(workspaceId)).orElse(null);
        if (client == null) {
            return AdminApi.problem(ProblemCode.NOT_FOUND, "MCP client not found", null, request);
        }
        DaiPrincipal caller = gate.caller();
        if (client.status() != McpClientStatus.PENDING) {
            return AdminApi.problem(ProblemCode.CONFLICT, "Not pending", "Only a pending client can be approved.",
                    request);
        }
        if (store.registeredBy(id).filter(caller.principalId()::equals).isPresent()) {
            return AdminApi.problem(ProblemCode.ACCESS_DENIED, "Separation of duties",
                    "The person who registered a client cannot approve it.", request);
        }
        McpClientView approved = store.approve(id, caller.principalId());
        audit.record(caller, AuditCategory.ADMIN, AuditPlane.CONTROL, "MCP_CLIENT_APPROVED", workspaceId,
                "mcp_client", id.toString(), null, null, Map.of("clientId", client.clientId()));
        return ResponseEntity.ok(ClientDto.of(approved));
    }

    /**
     * Revokes a client and every user consent given to it. Its tokens are refused from the next request.
     *
     * @param workspaceId workspace
     * @param id          client registration
     * @param request     current request
     * @return 200 with the client; 404 unknown
     */
    @PostMapping("/{id:[^:]+}:revoke")
    public ResponseEntity<?> revoke(@PathVariable UUID workspaceId, @PathVariable UUID id,
                                    HttpServletRequest request) {
        var gate = api.gate(request, Permission.SERVICEACCOUNT_MANAGE, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        McpClientView client = store.find(id).filter(c -> c.workspaceId().equals(workspaceId)).orElse(null);
        if (client == null) {
            return AdminApi.problem(ProblemCode.NOT_FOUND, "MCP client not found", null, request);
        }
        DaiPrincipal caller = gate.caller();
        if (store.revoke(id)) {
            audit.record(caller, AuditCategory.ADMIN, AuditPlane.CONTROL, "MCP_CLIENT_REVOKED", workspaceId,
                    "mcp_client", id.toString(), null, null, Map.of("clientId", client.clientId()));
        }
        return ResponseEntity.ok(ClientDto.of(store.find(id).orElse(client)));
    }
}
