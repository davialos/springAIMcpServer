package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.core.principal.FrameworkRole;
import com.springaimcpservercommon.persistence.audit.AuditCategory;
import com.springaimcpservercommon.persistence.audit.AuditPlane;
import com.springaimcpservercommon.persistence.identity.MemberView;
import com.springaimcpservercommon.persistence.identity.WorkspaceStore;
import com.springaimcpservercommon.persistence.identity.WorkspaceView;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Workspace and membership admin API (F-62, LLD-08 §2).
 *
 * <ul>
 *   <li>List/get: members see their own workspaces; {@link Permission#WORKSPACE_ADMIN} globally sees all.</li>
 *   <li>Create: global {@code workspace:admin}.</li>
 *   <li>Update, archive, members: {@code workspace:admin} in that workspace. Updates need {@code If-Match}.</li>
 *   <li>Members can only hold workspace-scoped roles (never platform-wide roles), and the last workspace
 *       owner cannot be removed.</li>
 * </ul>
 * Every change is audited after commit. Not a {@code @Component}; registered by
 * {@link DaiAdminAutoConfiguration}.
 */
@NullMarked
@RequestMapping("/dynamic-ai/admin/api/v1/workspaces")
public final class WorkspaceAdminController {

    static final Pattern SLUG = Pattern.compile("[a-z][a-z0-9-]{1,62}[a-z0-9]");
    static final int MAX_NAME = 120;
    static final int MAX_DESCRIPTION = 2000;
    static final int MAX_TENANT = 128;

    /**
     * Create request.
     *
     * @param slug        unique lowercase slug, 3..64 characters
     * @param name        display name, 1..120
     * @param description optional, up to 2000
     * @param tenantId    optional host tenant id, up to 128
     * @param clearance   data clearance (default INTERNAL)
     */
    public record CreateRequest(@Nullable String slug, @Nullable String name, @Nullable String description,
                                @Nullable String tenantId, @Nullable String clearance) {}

    /**
     * Update request; absent fields keep their current value.
     *
     * @param name        new display name
     * @param description new description
     * @param clearance   new clearance
     */
    public record UpdateRequest(@Nullable String name, @Nullable String description, @Nullable String clearance) {}

    /**
     * Add-member request.
     *
     * @param principalId member principal
     * @param role        workspace-scoped role
     * @param expiresAt   optional ISO-8601 expiry in the future
     */
    public record AddMemberRequest(@Nullable UUID principalId, @Nullable String role, @Nullable String expiresAt) {}

    /**
     * Workspace as shown to the UI.
     *
     * @param id          id
     * @param slug        slug
     * @param name        name
     * @param description description
     * @param tenantId    tenant
     * @param clearance   clearance
     * @param status      ACTIVE or ARCHIVED
     * @param createdAt   creation time
     * @param updatedAt   last update
     * @param version     row version (also the ETag)
     */
    public record WorkspaceDto(UUID id, String slug, String name, @Nullable String description,
                               @Nullable String tenantId, String clearance, String status, Instant createdAt,
                               Instant updatedAt, long version) {
        static WorkspaceDto of(WorkspaceView w) {
            return new WorkspaceDto(w.id(), w.slug(), w.name(), w.description(), w.tenantId(), w.clearance().name(),
                    w.status().name(), w.createdAt(), w.updatedAt(), w.rowVersion());
        }
    }

    /**
     * Member as shown to the UI.
     *
     * @param principalId member
     * @param role        role
     * @param grantedBy   who granted it
     * @param grantedAt   when
     * @param expiresAt   expiry, if any
     */
    public record MemberDto(UUID principalId, String role, @Nullable UUID grantedBy, Instant grantedAt,
                            @Nullable Instant expiresAt) {
        static MemberDto of(MemberView m) {
            return new MemberDto(m.principalId(), m.role().name(), m.grantedBy(), m.grantedAt(), m.expiresAt());
        }
    }

    private final WorkspaceStore store;
    private final AdminAudit audit;
    private final AdminApi api;
    private final Clock clock;

    WorkspaceAdminController(WorkspaceStore store, AdminAudit audit, AdminApi api, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.api = Objects.requireNonNull(api, "api");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Lists active workspaces visible to the caller.
     *
     * @param request current request
     * @return 200 with workspaces
     */
    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request) {
        var gate = api.authenticated(request);
        if (!gate.open()) {
            return gate.denied();
        }
        DaiPrincipal caller = gate.caller();
        boolean all = api.permits(caller, Permission.WORKSPACE_ADMIN, null);
        return ResponseEntity.ok(store.listActive().stream()
                .filter(w -> all || caller.workspaceRoles().containsKey(w.id()))
                .map(WorkspaceDto::of).toList());
    }

    /**
     * Returns one workspace.
     *
     * @param workspaceId workspace
     * @param request     current request
     * @return 200 with an ETag; 404 when unknown or the caller is not a member
     */
    @GetMapping("/{workspaceId}")
    public ResponseEntity<?> get(@PathVariable UUID workspaceId, HttpServletRequest request) {
        var gate = api.authenticated(request);
        if (!gate.open()) {
            return gate.denied();
        }
        DaiPrincipal caller = gate.caller();
        WorkspaceView w = store.find(workspaceId).orElse(null);
        boolean visible = w != null && (caller.workspaceRoles().containsKey(workspaceId)
                || api.permits(caller, Permission.WORKSPACE_ADMIN, null));
        if (!visible) {
            return AdminApi.problem(ProblemCode.NOT_FOUND, "Workspace not found", null, request);
        }
        return withEtag(HttpStatus.OK, w);
    }

    /**
     * Creates a workspace.
     *
     * @param body    workspace data
     * @param request current request
     * @return 201 with the workspace; 400 with field errors; 409 for a taken slug
     */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest body, HttpServletRequest request) {
        var gate = api.gate(request, Permission.WORKSPACE_ADMIN, null);
        if (!gate.open()) {
            return gate.denied();
        }
        List<FieldViolation> errors = new ArrayList<>();
        String slug = AdminApi.text(errors, "slug", body.slug(), true, 64);
        if (slug != null && !SLUG.matcher(slug).matches()) {
            errors.add(new FieldViolation("slug", "must match [a-z][a-z0-9-]{1,62}[a-z0-9]"));
        }
        String name = AdminApi.text(errors, "name", body.name(), true, MAX_NAME);
        String description = AdminApi.text(errors, "description", body.description(), false, MAX_DESCRIPTION);
        String tenantId = AdminApi.text(errors, "tenantId", body.tenantId(), false, MAX_TENANT);
        Classification clearance = clearance(errors, body.clearance(), Classification.INTERNAL);
        if (!errors.isEmpty()) {
            return AdminApi.validation(request, errors);
        }
        DaiPrincipal caller = gate.caller();
        WorkspaceView created = store.create(Objects.requireNonNull(slug), Objects.requireNonNull(name),
                description, tenantId, clearance, caller.principalId());
        audit.record(caller, AuditCategory.ADMIN, AuditPlane.CONTROL, "WORKSPACE_CREATED", created.id(),
                "workspace", created.id().toString(), null, null, Map.of("slug", created.slug()));
        return withEtag(HttpStatus.CREATED, created);
    }

    /**
     * Updates name, description or clearance. Requires {@code If-Match} with the row version last read.
     *
     * @param workspaceId workspace
     * @param ifMatch     row version
     * @param body        new values
     * @param request     current request
     * @return 200 with the workspace; 412 when stale; 428 when {@code If-Match} is missing
     */
    @PatchMapping("/{workspaceId}")
    public ResponseEntity<?> update(@PathVariable UUID workspaceId,
                                    @RequestHeader(value = "If-Match", required = false) @Nullable String ifMatch,
                                    @RequestBody UpdateRequest body, HttpServletRequest request) {
        var gate = api.gate(request, Permission.WORKSPACE_ADMIN, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        Long version = AdminApi.ifMatch(ifMatch);
        if (version == null) {
            return AdminApi.problem(ProblemCode.PRECONDITION_REQUIRED, "If-Match required",
                    "Send the row version you last read in If-Match.", request);
        }
        WorkspaceView current = store.find(workspaceId).orElse(null);
        if (current == null) {
            return AdminApi.problem(ProblemCode.NOT_FOUND, "Workspace not found", null, request);
        }
        List<FieldViolation> errors = new ArrayList<>();
        String name = body.name() == null ? current.name()
                : AdminApi.text(errors, "name", body.name(), true, MAX_NAME);
        String description = body.description() == null ? current.description()
                : AdminApi.text(errors, "description", body.description(), false, MAX_DESCRIPTION);
        Classification clearance = clearance(errors, body.clearance(), current.clearance());
        if (!errors.isEmpty()) {
            return AdminApi.validation(request, errors);
        }
        DaiPrincipal caller = gate.caller();
        WorkspaceView updated = store.update(workspaceId, version, Objects.requireNonNull(name), description, clearance,
                caller.principalId());
        audit.record(caller, AuditCategory.ADMIN, AuditPlane.CONTROL, "WORKSPACE_UPDATED", workspaceId, "workspace",
                workspaceId.toString(), null, null, Map.of("clearance", updated.clearance().name()));
        return withEtag(HttpStatus.OK, updated);
    }

    /**
     * Archives a workspace.
     *
     * @param workspaceId workspace
     * @param request     current request
     * @return 204 when archived
     */
    @DeleteMapping("/{workspaceId}")
    public ResponseEntity<?> archive(@PathVariable UUID workspaceId, HttpServletRequest request) {
        var gate = api.gate(request, Permission.WORKSPACE_ADMIN, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        DaiPrincipal caller = gate.caller();
        store.archive(workspaceId, caller.principalId());
        audit.record(caller, AuditCategory.ADMIN, AuditPlane.CONTROL, "WORKSPACE_ARCHIVED", workspaceId, "workspace",
                workspaceId.toString(), null, null, Map.of());
        return ResponseEntity.noContent().build();
    }

    /**
     * Lists the members of a workspace.
     *
     * @param workspaceId workspace
     * @param request     current request
     * @return 200 with members
     */
    @GetMapping("/{workspaceId}/members")
    public ResponseEntity<?> members(@PathVariable UUID workspaceId, HttpServletRequest request) {
        var gate = api.gate(request, Permission.WORKSPACE_ADMIN, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        return ResponseEntity.ok(store.members(workspaceId).stream().map(MemberDto::of).toList());
    }

    /**
     * Adds a member, or changes the expiry of an existing membership.
     *
     * @param workspaceId workspace
     * @param body        member, workspace-scoped role and optional expiry
     * @param request     current request
     * @return 201 with the member
     */
    @PostMapping("/{workspaceId}/members")
    public ResponseEntity<?> addMember(@PathVariable UUID workspaceId, @RequestBody AddMemberRequest body,
                                       HttpServletRequest request) {
        var gate = api.gate(request, Permission.WORKSPACE_ADMIN, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        List<FieldViolation> errors = new ArrayList<>();
        if (body.principalId() == null) {
            errors.add(new FieldViolation("principalId", "is required"));
        }
        FrameworkRole role = role(errors, body.role());
        Instant expiresAt = null;
        if (body.expiresAt() != null && !body.expiresAt().isBlank()) {
            try {
                expiresAt = AdminApi.instant(body.expiresAt(), "expiresAt");
                if (!expiresAt.isAfter(clock.instant())) {
                    errors.add(new FieldViolation("expiresAt", "must be in the future"));
                }
            } catch (IllegalArgumentException e) {
                errors.add(new FieldViolation("expiresAt", "must be an ISO-8601 instant"));
            }
        }
        if (!errors.isEmpty()) {
            return AdminApi.validation(request, errors);
        }
        DaiPrincipal caller = gate.caller();
        MemberView member = store.addMember(workspaceId, Objects.requireNonNull(body.principalId()),
                Objects.requireNonNull(role), caller.principalId(), expiresAt);
        audit.record(caller, AuditCategory.ADMIN, AuditPlane.CONTROL, "WORKSPACE_MEMBER_ADDED", workspaceId,
                "workspace_member", member.principalId().toString(), null, null, Map.of("role", member.role().name()));
        return ResponseEntity.status(HttpStatus.CREATED).body(MemberDto.of(member));
    }

    /**
     * Removes a membership. The last {@code WORKSPACE_OWNER} cannot be removed.
     *
     * @param workspaceId workspace
     * @param principalId member
     * @param role        role to remove
     * @param request     current request
     * @return 204 when removed; 404 when there was no such membership; 409 for the last owner
     */
    @DeleteMapping("/{workspaceId}/members/{principalId}/{role}")
    public ResponseEntity<?> removeMember(@PathVariable UUID workspaceId, @PathVariable UUID principalId,
                                          @PathVariable String role, HttpServletRequest request) {
        var gate = api.gate(request, Permission.WORKSPACE_ADMIN, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        List<FieldViolation> errors = new ArrayList<>();
        FrameworkRole parsed = role(errors, role);
        if (!errors.isEmpty()) {
            return AdminApi.validation(request, errors);
        }
        if (parsed == FrameworkRole.WORKSPACE_OWNER && store.members(workspaceId).stream()
                .filter(m -> m.role() == FrameworkRole.WORKSPACE_OWNER).count() <= 1) {
            return AdminApi.problem(ProblemCode.CONFLICT, "Last owner",
                    "A workspace must keep at least one WORKSPACE_OWNER.", request);
        }
        DaiPrincipal caller = gate.caller();
        if (!store.removeMember(workspaceId, principalId, Objects.requireNonNull(parsed))) {
            return AdminApi.problem(ProblemCode.NOT_FOUND, "Membership not found", null, request);
        }
        audit.record(caller, AuditCategory.ADMIN, AuditPlane.CONTROL, "WORKSPACE_MEMBER_REMOVED", workspaceId,
                "workspace_member", principalId.toString(), null, null, Map.of("role", parsed.name()));
        return ResponseEntity.noContent().build();
    }

    private static @Nullable FrameworkRole role(List<FieldViolation> errors, @Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            errors.add(new FieldViolation("role", "is required"));
            return null;
        }
        FrameworkRole role;
        try {
            role = FrameworkRole.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add(new FieldViolation("role", "is not a known role"));
            return null;
        }
        if (role.globalOnly()) {
            errors.add(new FieldViolation("role", "platform-wide roles cannot be held as workspace membership"));
            return null;
        }
        return role;
    }

    private static Classification clearance(List<FieldViolation> errors, @Nullable String raw, Classification dflt) {
        if (raw == null || raw.isBlank()) {
            return dflt;
        }
        try {
            Classification c = Classification.valueOf(raw.strip().toUpperCase(Locale.ROOT));
            if (c == Classification.INHERIT) {
                errors.add(new FieldViolation("clearance", "must be a concrete classification"));
                return dflt;
            }
            return c;
        } catch (IllegalArgumentException e) {
            errors.add(new FieldViolation("clearance", "must be PUBLIC, INTERNAL, CONFIDENTIAL or RESTRICTED"));
            return dflt;
        }
    }

    private static ResponseEntity<WorkspaceDto> withEtag(HttpStatus status, WorkspaceView w) {
        return ResponseEntity.status(status).eTag("\"" + w.rowVersion() + "\"").body(WorkspaceDto.of(w));
    }
}
