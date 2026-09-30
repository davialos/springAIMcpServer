package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.persistence.audit.AuditCategory;
import com.springaimcpservercommon.persistence.audit.AuditPlane;
import com.springaimcpservercommon.persistence.identity.ApiKeyStore;
import com.springaimcpservercommon.persistence.identity.ApiKeyView;
import com.springaimcpservercommon.persistence.identity.ServiceAccountView;
import com.springaimcpservercommon.security.permission.Permission;
import com.springaimcpservercommon.webmvc.problem.ProblemCode;
import com.springaimcpservercommon.webmvc.problem.ProblemDetailFactory.FieldViolation;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Service account and API key admin API (F-65). Requires {@link Permission#SERVICEACCOUNT_MANAGE} in the
 * workspace. Key listings never include the stored hash or any secret.
 *
 * <p>Issuing a new key is intentionally not exposed yet: it needs the security module's
 * {@code ApiKeyService} (pepper provider), which has no auto-configuration (OQ-37). Revoking keys works
 * today; verified keys may stay usable for up to the verification cache TTL (30 s) after revocation.
 *
 * <p>Not a {@code @Component}; registered by {@link DaiAdminAutoConfiguration}.
 */
@NullMarked
@RequestMapping("/dynamic-ai/admin/api/v1/workspaces/{workspaceId}/service-accounts")
public final class ServiceAccountAdminController {

    static final int MAX_NAME = 120;
    static final int MAX_DESCRIPTION = 500;

    /**
     * Create request.
     *
     * @param name        display name, 1..120
     * @param description optional, up to 500
     */
    public record CreateRequest(@Nullable String name, @Nullable String description) {}

    /**
     * Service account as shown to the UI.
     *
     * @param id          id
     * @param principalId principal used in authorization and audit
     * @param name        name
     * @param description description
     * @param status      status
     * @param createdAt   creation time
     */
    public record AccountDto(UUID id, UUID principalId, String name, @Nullable String description, String status,
                             Instant createdAt) {
        static AccountDto of(ServiceAccountView v) {
            return new AccountDto(v.id(), v.principalId(), v.name(), v.description(), v.status().name(),
                    v.createdAt());
        }
    }

    /**
     * API key metadata; contains no secret and no hash.
     *
     * @param id              key id
     * @param keyPrefix       public prefix
     * @param expiresAt       expiry
     * @param lastUsedAt      last use (throttled)
     * @param revokedAt       revocation time, if revoked
     * @param scopes          scopes
     * @param allowedNetworks allowed CIDR networks
     * @param active          not revoked and not expired
     */
    public record KeyDto(UUID id, String keyPrefix, Instant expiresAt, @Nullable Instant lastUsedAt,
                         @Nullable Instant revokedAt, Set<String> scopes, List<String> allowedNetworks,
                         boolean active) {
        static KeyDto of(ApiKeyView k, Instant now) {
            return new KeyDto(k.id(), k.keyPrefix(), k.expiresAt(), k.lastUsedAt(), k.revokedAt(),
                    new TreeSet<>(k.scopes()), k.allowedNetworks(), k.revokedAt() == null && k.expiresAt().isAfter(now));
        }
    }

    private final ApiKeyStore store;
    private final AdminAudit audit;
    private final AdminApi api;
    private final Clock clock;

    private final Runnable identityChanged;

    ServiceAccountAdminController(ApiKeyStore store, AdminAudit audit, AdminApi api, Clock clock) {
        this(store, audit, api, clock, () -> { });
    }

    /**
     * @param identityChanged called after a change that alters callers' roles (drops the principal-mapping cache)
     */
    ServiceAccountAdminController(ApiKeyStore store, AdminAudit audit, AdminApi api, Clock clock, Runnable identityChanged) {
        this.identityChanged = Objects.requireNonNull(identityChanged, "identityChanged");
        this.store = Objects.requireNonNull(store, "store");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.api = Objects.requireNonNull(api, "api");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Lists the service accounts of a workspace.
     *
     * @param workspaceId workspace
     * @param request     current request
     * @return 200 with accounts
     */
    @GetMapping
    public ResponseEntity<?> list(@PathVariable UUID workspaceId, HttpServletRequest request) {
        var gate = api.gate(request, Permission.SERVICEACCOUNT_MANAGE, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        return ResponseEntity.ok(store.serviceAccounts(workspaceId).stream().map(AccountDto::of).toList());
    }

    /**
     * Creates a service account.
     *
     * @param workspaceId workspace
     * @param body        name and description
     * @param request     current request
     * @return 201 with the account; 400 with field errors
     */
    @PostMapping
    public ResponseEntity<?> create(@PathVariable UUID workspaceId, @RequestBody CreateRequest body,
                                    HttpServletRequest request) {
        var gate = api.gate(request, Permission.SERVICEACCOUNT_MANAGE, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        List<FieldViolation> errors = new ArrayList<>();
        String name = AdminApi.text(errors, "name", body.name(), true, MAX_NAME);
        String description = AdminApi.text(errors, "description", body.description(), false, MAX_DESCRIPTION);
        if (!errors.isEmpty()) {
            return AdminApi.validation(request, errors);
        }
        DaiPrincipal caller = gate.caller();
        ServiceAccountView created = store.createServiceAccount(workspaceId, Objects.requireNonNull(name),
                description, caller.principalId());
        audit.record(caller, AuditCategory.ADMIN, AuditPlane.CONTROL, "SERVICE_ACCOUNT_CREATED", workspaceId,
                "service_account", created.id().toString(), null, null, Map.of());
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountDto.of(created));
    }

    /**
     * Enables a service account.
     *
     * @param workspaceId workspace
     * @param id          service account
     * @param request     current request
     * @return 200 with the account
     */
    @PostMapping("/{id:[^:]+}:enable")
    public ResponseEntity<?> enable(@PathVariable UUID workspaceId, @PathVariable UUID id,
                                    HttpServletRequest request) {
        return setEnabled(workspaceId, id, true, request);
    }

    /**
     * Disables a service account; its keys stop authenticating.
     *
     * @param workspaceId workspace
     * @param id          service account
     * @param request     current request
     * @return 200 with the account
     */
    @PostMapping("/{id:[^:]+}:disable")
    public ResponseEntity<?> disable(@PathVariable UUID workspaceId, @PathVariable UUID id,
                                     HttpServletRequest request) {
        return setEnabled(workspaceId, id, false, request);
    }

    /**
     * Lists the keys of a service account (metadata only).
     *
     * @param workspaceId workspace
     * @param id          service account
     * @param request     current request
     * @return 200 with key metadata
     */
    @GetMapping("/{id}/keys")
    public ResponseEntity<?> keys(@PathVariable UUID workspaceId, @PathVariable UUID id,
                                  HttpServletRequest request) {
        var gate = api.gate(request, Permission.SERVICEACCOUNT_MANAGE, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        if (!inWorkspace(workspaceId, id)) {
            return AdminApi.problem(ProblemCode.NOT_FOUND, "Service account not found", null, request);
        }
        Instant now = clock.instant();
        return ResponseEntity.ok(store.keysOf(id).stream().map(k -> KeyDto.of(k, now)).toList());
    }

    /**
     * Revokes a key. Idempotent for already revoked keys (404 is returned only for unknown keys).
     *
     * @param workspaceId workspace
     * @param id          service account
     * @param keyId       key
     * @param request     current request
     * @return 204 when revoked or already revoked
     */
    @DeleteMapping("/{id}/keys/{keyId}")
    public ResponseEntity<?> revokeKey(@PathVariable UUID workspaceId, @PathVariable UUID id,
                                       @PathVariable UUID keyId, HttpServletRequest request) {
        var gate = api.gate(request, Permission.SERVICEACCOUNT_MANAGE, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        if (!inWorkspace(workspaceId, id) || store.keysOf(id).stream().noneMatch(k -> k.id().equals(keyId))) {
            return AdminApi.problem(ProblemCode.NOT_FOUND, "API key not found", null, request);
        }
        DaiPrincipal caller = gate.caller();
        if (store.revoke(keyId, caller.principalId())) {
            audit.record(caller, AuditCategory.ADMIN, AuditPlane.CONTROL, "API_KEY_REVOKED", workspaceId, "api_key",
                    keyId.toString(), null, null, Map.of("serviceAccountId", id.toString()));
        }
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<?> setEnabled(UUID workspaceId, UUID id, boolean enabled, HttpServletRequest request) {
        var gate = api.gate(request, Permission.SERVICEACCOUNT_MANAGE, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        if (!inWorkspace(workspaceId, id)) {
            return AdminApi.problem(ProblemCode.NOT_FOUND, "Service account not found", null, request);
        }
        DaiPrincipal caller = gate.caller();
        ServiceAccountView updated = store.setServiceAccountEnabled(id, enabled, caller.principalId());
        audit.record(caller, AuditCategory.ADMIN, AuditPlane.CONTROL,
                enabled ? "SERVICE_ACCOUNT_ENABLED" : "SERVICE_ACCOUNT_DISABLED", workspaceId, "service_account",
                id.toString(), null, null, Map.of());
        identityChanged.run();
        return ResponseEntity.ok(AccountDto.of(updated));
    }

    private boolean inWorkspace(UUID workspaceId, UUID serviceAccountId) {
        return store.findServiceAccount(serviceAccountId).filter(a -> a.workspaceId().equals(workspaceId)).isPresent();
    }
}
