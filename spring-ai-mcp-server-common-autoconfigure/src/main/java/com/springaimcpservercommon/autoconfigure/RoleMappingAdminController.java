package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.core.principal.FrameworkRole;
import com.springaimcpservercommon.persistence.audit.AuditCategory;
import com.springaimcpservercommon.persistence.audit.AuditPlane;
import com.springaimcpservercommon.persistence.identity.RoleMappingRule;
import com.springaimcpservercommon.persistence.identity.RoleMappingSource;
import com.springaimcpservercommon.persistence.identity.RoleMappingStore;
import com.springaimcpservercommon.persistence.identity.RoleMappingView;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Role mapping admin API (F-61): maps IdP groups, claims, authorities or scopes to framework roles.
 * Requires {@link Permission#ROLEMAPPING_MANAGE} (global).
 *
 * <p>Privilege-escalation guard: creating, changing, enabling or deleting a mapping that yields a
 * platform-wide role ({@link FrameworkRole#globalOnly()}) additionally requires the caller to hold
 * {@link FrameworkRole#PLATFORM_ADMIN}, so a security admin cannot mint platform admins.
 *
 * <p>Not a {@code @Component}; registered by {@link DaiAdminAutoConfiguration}.
 */
@NullMarked
@RequestMapping("/dynamic-ai/admin/api/v1/role-mappings")
public final class RoleMappingAdminController {

    static final int MAX_MATCH = 512;
    static final int MAX_CLAIM = 128;
    static final int MAX_ISSUER = 512;
    static final int MAX_DESCRIPTION = 500;
    static final int MAX_PRIORITY = 10_000;

    /**
     * Create or replace request.
     *
     * @param source      AUTHORITY, OIDC_CLAIM, LDAP_GROUP or SCOPE
     * @param issuer      optional issuer the mapping applies to
     * @param claimName   claim name; required for OIDC_CLAIM, forbidden otherwise
     * @param matchValue  value to match (group name, DN, authority, scope), 1..512
     * @param role        framework role granted
     * @param workspaceId workspace for workspace-scoped roles; forbidden for platform-wide roles
     * @param priority    0..10000, default 100
     * @param description optional text, up to 500
     */
    public record MappingRequest(@Nullable String source, @Nullable String issuer, @Nullable String claimName,
                                 @Nullable String matchValue, @Nullable String role, @Nullable UUID workspaceId,
                                 @Nullable Integer priority, @Nullable String description) {}

    /**
     * Mapping as shown to the UI.
     *
     * @param id          id
     * @param source      source
     * @param issuer      issuer
     * @param claimName   claim name
     * @param matchValue  match value
     * @param role        role
     * @param workspaceId workspace
     * @param priority    priority
     * @param enabled     whether in force
     * @param description description
     * @param createdAt   creation time
     * @param updatedAt   last update
     * @param version     row version (also the ETag)
     */
    public record MappingDto(UUID id, String source, @Nullable String issuer, @Nullable String claimName,
                             String matchValue, String role, @Nullable UUID workspaceId, int priority,
                             boolean enabled, @Nullable String description, Instant createdAt, Instant updatedAt,
                             long version) {
        static MappingDto of(RoleMappingView v) {
            RoleMappingRule r = v.rule();
            return new MappingDto(v.id(), r.source().name(), r.issuer(), r.claimName(), r.matchValue(),
                    r.role().name(), r.workspaceId(), r.priority(), v.enabled(), v.description(), v.createdAt(),
                    v.updatedAt(), v.rowVersion());
        }
    }

    private final RoleMappingStore store;
    private final AdminAudit audit;
    private final AdminApi api;

    private final Runnable identityChanged;

    RoleMappingAdminController(RoleMappingStore store, AdminAudit audit, AdminApi api) {
        this(store, audit, api, () -> { });
    }

    /**
     * @param identityChanged called after a change that alters callers' roles (drops the principal-mapping cache)
     */
    RoleMappingAdminController(RoleMappingStore store, AdminAudit audit, AdminApi api, Runnable identityChanged) {
        this.identityChanged = Objects.requireNonNull(identityChanged, "identityChanged");
        this.store = Objects.requireNonNull(store, "store");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.api = Objects.requireNonNull(api, "api");
    }

    /**
     * Lists all mappings.
     *
     * @param request current request
     * @return 200 with mappings
     */
    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request) {
        var gate = api.gate(request, Permission.ROLEMAPPING_MANAGE, null);
        if (!gate.open()) {
            return gate.denied();
        }
        return ResponseEntity.ok(store.list().stream().map(MappingDto::of).toList());
    }

    /**
     * Returns one mapping.
     *
     * @param id      mapping id
     * @param request current request
     * @return 200 with an ETag; 404 when unknown
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable UUID id, HttpServletRequest request) {
        var gate = api.gate(request, Permission.ROLEMAPPING_MANAGE, null);
        if (!gate.open()) {
            return gate.denied();
        }
        RoleMappingView v = store.find(id).orElse(null);
        return v == null ? AdminApi.problem(ProblemCode.NOT_FOUND, "Role mapping not found", null, request)
                : etag(HttpStatus.OK, v);
    }

    /**
     * Creates a mapping.
     *
     * @param body    mapping definition
     * @param request current request
     * @return 201 with the mapping; 400 with field errors; 403 when minting a platform-wide role without
     *         {@code PLATFORM_ADMIN}
     */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody MappingRequest body, HttpServletRequest request) {
        var gate = api.gate(request, Permission.ROLEMAPPING_MANAGE, null);
        if (!gate.open()) {
            return gate.denied();
        }
        List<FieldViolation> errors = new ArrayList<>();
        RoleMappingRule rule = rule(body, errors);
        if (rule == null || !errors.isEmpty()) {
            return AdminApi.validation(request, errors);
        }
        DaiPrincipal caller = gate.caller();
        if (!mayManage(caller, rule.role())) {
            return escalationDenied(request);
        }
        String description = AdminApi.text(errors, "description", body.description(), false, MAX_DESCRIPTION);
        if (!errors.isEmpty()) {
            return AdminApi.validation(request, errors);
        }
        RoleMappingView created = store.create(rule, description, caller.principalId());
        record(caller, "ROLE_MAPPING_CREATED", created);
        identityChanged.run();
        return etag(HttpStatus.CREATED, created);
    }

    /**
     * Replaces a mapping. Requires {@code If-Match} with the row version last read.
     *
     * @param id      mapping id
     * @param ifMatch row version
     * @param body    new definition
     * @param request current request
     * @return 200 with the mapping; 412 when stale; 428 when {@code If-Match} is missing
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> replace(@PathVariable UUID id,
                                     @RequestHeader(value = "If-Match", required = false) @Nullable String ifMatch,
                                     @RequestBody MappingRequest body, HttpServletRequest request) {
        var gate = api.gate(request, Permission.ROLEMAPPING_MANAGE, null);
        if (!gate.open()) {
            return gate.denied();
        }
        Long version = AdminApi.ifMatch(ifMatch);
        if (version == null) {
            return AdminApi.problem(ProblemCode.PRECONDITION_REQUIRED, "If-Match required",
                    "Send the row version you last read in If-Match.", request);
        }
        RoleMappingView current = store.find(id).orElse(null);
        if (current == null) {
            return AdminApi.problem(ProblemCode.NOT_FOUND, "Role mapping not found", null, request);
        }
        List<FieldViolation> errors = new ArrayList<>();
        RoleMappingRule rule = rule(body, errors);
        String description = AdminApi.text(errors, "description", body.description(), false, MAX_DESCRIPTION);
        if (rule == null || !errors.isEmpty()) {
            return AdminApi.validation(request, errors);
        }
        DaiPrincipal caller = gate.caller();
        if (!mayManage(caller, rule.role()) || !mayManage(caller, current.rule().role())) {
            return escalationDenied(request);
        }
        RoleMappingView updated = store.update(id, version, rule, description, caller.principalId());
        record(caller, "ROLE_MAPPING_UPDATED", updated);
        identityChanged.run();
        return etag(HttpStatus.OK, updated);
    }

    /**
     * Enables a mapping.
     *
     * @param id      mapping id
     * @param request current request
     * @return 200 with the mapping
     */
    @PostMapping("/{id:[^:]+}:enable")
    public ResponseEntity<?> enable(@PathVariable UUID id, HttpServletRequest request) {
        return setEnabled(id, true, request);
    }

    /**
     * Disables a mapping without deleting it.
     *
     * @param id      mapping id
     * @param request current request
     * @return 200 with the mapping
     */
    @PostMapping("/{id:[^:]+}:disable")
    public ResponseEntity<?> disable(@PathVariable UUID id, HttpServletRequest request) {
        return setEnabled(id, false, request);
    }

    /**
     * Deletes a mapping.
     *
     * @param id      mapping id
     * @param request current request
     * @return 204 when deleted; 404 when unknown
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable UUID id, HttpServletRequest request) {
        var gate = api.gate(request, Permission.ROLEMAPPING_MANAGE, null);
        if (!gate.open()) {
            return gate.denied();
        }
        RoleMappingView current = store.find(id).orElse(null);
        if (current == null) {
            return AdminApi.problem(ProblemCode.NOT_FOUND, "Role mapping not found", null, request);
        }
        DaiPrincipal caller = gate.caller();
        if (!mayManage(caller, current.rule().role())) {
            return escalationDenied(request);
        }
        store.delete(id);
        record(caller, "ROLE_MAPPING_DELETED", current);
        identityChanged.run();
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<?> setEnabled(UUID id, boolean enabled, HttpServletRequest request) {
        var gate = api.gate(request, Permission.ROLEMAPPING_MANAGE, null);
        if (!gate.open()) {
            return gate.denied();
        }
        RoleMappingView current = store.find(id).orElse(null);
        if (current == null) {
            return AdminApi.problem(ProblemCode.NOT_FOUND, "Role mapping not found", null, request);
        }
        DaiPrincipal caller = gate.caller();
        if (!mayManage(caller, current.rule().role())) {
            return escalationDenied(request);
        }
        RoleMappingView updated = store.setEnabled(id, enabled, caller.principalId());
        record(caller, enabled ? "ROLE_MAPPING_ENABLED" : "ROLE_MAPPING_DISABLED", updated);
        identityChanged.run();
        identityChanged.run();
        return etag(HttpStatus.OK, updated);
    }

    private static boolean mayManage(DaiPrincipal caller, FrameworkRole role) {
        return !role.globalOnly() || caller.globalRoles().contains(FrameworkRole.PLATFORM_ADMIN);
    }

    private static ResponseEntity<String> escalationDenied(HttpServletRequest request) {
        return AdminApi.problem(ProblemCode.ACCESS_DENIED, "Access denied",
                "Only a platform administrator can manage mappings that grant platform-wide roles.", request);
    }

    private static @Nullable RoleMappingRule rule(MappingRequest body, List<FieldViolation> errors) {
        RoleMappingSource source = null;
        if (body.source() == null || body.source().isBlank()) {
            errors.add(new FieldViolation("source", "is required"));
        } else {
            try {
                source = RoleMappingSource.valueOf(body.source().strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                errors.add(new FieldViolation("source", "must be AUTHORITY, OIDC_CLAIM, LDAP_GROUP or SCOPE"));
            }
        }
        FrameworkRole role = null;
        if (body.role() == null || body.role().isBlank()) {
            errors.add(new FieldViolation("role", "is required"));
        } else {
            try {
                role = FrameworkRole.valueOf(body.role().strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                errors.add(new FieldViolation("role", "is not a known role"));
            }
        }
        String issuer = AdminApi.text(errors, "issuer", body.issuer(), false, MAX_ISSUER);
        String claim = AdminApi.text(errors, "claimName", body.claimName(), false, MAX_CLAIM);
        String match = AdminApi.text(errors, "matchValue", body.matchValue(), true, MAX_MATCH);
        int priority = body.priority() == null ? RoleMappingRule.DEFAULT_PRIORITY : body.priority();
        if (priority < 0 || priority > MAX_PRIORITY) {
            errors.add(new FieldViolation("priority", "must be between 0 and " + MAX_PRIORITY));
        }
        if (source == null || role == null || match == null || !errors.isEmpty()) {
            return null;
        }
        try {
            return new RoleMappingRule(source, issuer, claim, match, role, body.workspaceId(), priority);
        } catch (IllegalArgumentException e) {
            errors.add(new FieldViolation("rule", e.getMessage() == null ? "is inconsistent" : e.getMessage()));
            return null;
        }
    }

    private void record(DaiPrincipal caller, String action, RoleMappingView v) {
        audit.record(caller, AuditCategory.ADMIN, AuditPlane.CONTROL, action, v.rule().workspaceId(),
                "role_mapping", v.id().toString(), null, null,
                Map.of("role", v.rule().role().name(), "source", v.rule().source().name()));
    }

    private static ResponseEntity<MappingDto> etag(HttpStatus status, RoleMappingView v) {
        return ResponseEntity.status(status).eTag("\"" + v.rowVersion() + "\"").body(MappingDto.of(v));
    }
}
