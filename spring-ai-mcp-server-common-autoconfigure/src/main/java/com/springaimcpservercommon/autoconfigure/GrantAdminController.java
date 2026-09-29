package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.json.CanonicalJson;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.persistence.audit.AuditCategory;
import com.springaimcpservercommon.persistence.audit.AuditPlane;
import com.springaimcpservercommon.persistence.config.GrantStore;
import com.springaimcpservercommon.persistence.config.GrantTarget;
import com.springaimcpservercommon.persistence.config.GrantView;
import com.springaimcpservercommon.security.authz.condition.ConditionParseException;
import com.springaimcpservercommon.security.authz.condition.ConditionParser;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Fine-grained grant admin API (F-63): who may invoke which endpoint, agent or tool in a workspace. Requires
 * {@link Permission#GRANT_MANAGE} in the workspace. Only invocation-style permissions
 * ({@link Permission.Kind#GRANT}) can be granted; role permissions come from roles and role mappings.
 *
 * <p>Not a {@code @Component}; registered by {@link DaiAdminAutoConfiguration}.
 */
@NullMarked
@RequestMapping("/dynamic-ai/admin/api/v1/workspaces/{workspaceId}/grants")
public final class GrantAdminController {

    static final Duration MAX_EXPIRY = Duration.ofDays(5 * 365);
    static final int MAX_PATTERN = 256;

    /**
     * Create request.
     *
     * @param principalId  user, group or service account receiving the grant
     * @param permission   grantable permission value, e.g. {@code endpoint:invoke}
     * @param targetType   {@code WORKSPACE} (default), {@code RESOURCE} or {@code PATTERN}
     * @param resourceId   required for {@code RESOURCE}
     * @param pattern      required for {@code PATTERN}, e.g. {@code agent/support-*}
     * @param conditions   optional ABAC condition object; validated with the grant condition parser
     * @param expiresAt    optional ISO-8601 expiry in the future and within five years
     */
    public record CreateRequest(@Nullable UUID principalId, @Nullable String permission, @Nullable String targetType,
                                @Nullable UUID resourceId, @Nullable String pattern,
                                @Nullable Map<String, Object> conditions, @Nullable String expiresAt) {}

    /**
     * Grant as shown to the UI.
     *
     * @param id             id
     * @param principalId    grantee
     * @param permission     permission value
     * @param targetType     WORKSPACE, RESOURCE or PATTERN
     * @param resourceId     resource, for RESOURCE
     * @param pattern        pattern, for PATTERN
     * @param conditionsJson stored condition object, if any
     * @param expiresAt      expiry, if any
     * @param createdAt      creation time
     * @param createdBy      granting principal
     */
    public record GrantDto(UUID id, UUID principalId, String permission, String targetType,
                           @Nullable UUID resourceId, @Nullable String pattern, @Nullable String conditionsJson,
                           @Nullable Instant expiresAt, Instant createdAt, UUID createdBy) {
        static GrantDto of(GrantView g) {
            String type = "WORKSPACE";
            UUID resourceId = null;
            String pattern = null;
            if (g.target() instanceof GrantTarget.OnResource r) {
                type = "RESOURCE";
                resourceId = r.resourceId();
            } else if (g.target() instanceof GrantTarget.OnPattern p) {
                type = "PATTERN";
                pattern = p.pattern();
            }
            return new GrantDto(g.id(), g.principalId(), g.permission(), type, resourceId, pattern,
                    g.conditionsJson(), g.expiresAt(), g.createdAt(), g.createdBy());
        }
    }

    private final GrantStore store;
    private final AdminAudit audit;
    private final AdminApi api;
    private final Clock clock;
    private final ConditionParser conditionParser = new ConditionParser();

    GrantAdminController(GrantStore store, AdminAudit audit, AdminApi api, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.api = Objects.requireNonNull(api, "api");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Lists the grants of a workspace.
     *
     * @param workspaceId workspace
     * @param request     current request
     * @return 200 with grants
     */
    @GetMapping
    public ResponseEntity<?> list(@PathVariable UUID workspaceId, HttpServletRequest request) {
        var gate = api.gate(request, Permission.GRANT_MANAGE, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        return ResponseEntity.ok(store.grantsInWorkspace(workspaceId).stream().map(GrantDto::of).toList());
    }

    /**
     * Creates a grant.
     *
     * @param workspaceId workspace
     * @param body        grant definition
     * @param request     current request
     * @return 201 with the grant; 400 with field errors
     */
    @PostMapping
    public ResponseEntity<?> create(@PathVariable UUID workspaceId, @RequestBody CreateRequest body,
                                    HttpServletRequest request) {
        var gate = api.gate(request, Permission.GRANT_MANAGE, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        List<FieldViolation> errors = new ArrayList<>();
        if (body.principalId() == null) {
            errors.add(new FieldViolation("principalId", "is required"));
        }
        Permission permission = null;
        if (body.permission() == null || body.permission().isBlank()) {
            errors.add(new FieldViolation("permission", "is required"));
        } else {
            permission = Permission.fromValue(body.permission().strip().toLowerCase(Locale.ROOT)).orElse(null);
            if (permission == null || permission.kind() != Permission.Kind.GRANT) {
                errors.add(new FieldViolation("permission", "must be a grantable invocation permission"));
                permission = null;
            }
        }
        GrantTarget target = target(body, errors);
        String conditionsJson = null;
        if (body.conditions() != null && !body.conditions().isEmpty()) {
            conditionsJson = CanonicalJson.write(body.conditions());
            try {
                conditionParser.parse(conditionsJson);
            } catch (ConditionParseException e) {
                errors.add(new FieldViolation("conditions", "are invalid or use unsupported operators"));
                conditionsJson = null;
            }
        }
        Instant now = clock.instant();
        Instant expiresAt = null;
        if (body.expiresAt() != null && !body.expiresAt().isBlank()) {
            try {
                expiresAt = AdminApi.instant(body.expiresAt(), "expiresAt");
                if (!expiresAt.isAfter(now) || expiresAt.isAfter(now.plus(MAX_EXPIRY))) {
                    errors.add(new FieldViolation("expiresAt", "must be in the future and within five years"));
                }
            } catch (IllegalArgumentException e) {
                errors.add(new FieldViolation("expiresAt", "must be an ISO-8601 instant"));
            }
        }
        if (!errors.isEmpty() || permission == null || target == null) {
            return AdminApi.validation(request, errors);
        }
        DaiPrincipal caller = gate.caller();
        GrantView created = store.create(workspaceId, Objects.requireNonNull(body.principalId()), permission.value(),
                target, conditionsJson, expiresAt, caller.principalId());
        audit.record(caller, AuditCategory.ADMIN, AuditPlane.CONTROL, "GRANT_CREATED", workspaceId, "grant",
                created.id().toString(), null, null,
                Map.of("permission", created.permission(), "grantee", created.principalId().toString()));
        return ResponseEntity.status(HttpStatus.CREATED).body(GrantDto.of(created));
    }

    /**
     * Revokes a grant.
     *
     * @param workspaceId workspace
     * @param grantId     grant
     * @param request     current request
     * @return 204 when revoked; 404 when the grant is not in this workspace
     */
    @DeleteMapping("/{grantId}")
    public ResponseEntity<?> revoke(@PathVariable UUID workspaceId, @PathVariable UUID grantId,
                                    HttpServletRequest request) {
        var gate = api.gate(request, Permission.GRANT_MANAGE, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        GrantView grant = store.grantsInWorkspace(workspaceId).stream().filter(g -> g.id().equals(grantId))
                .findFirst().orElse(null);
        if (grant == null || !store.revoke(grantId)) {
            return AdminApi.problem(ProblemCode.NOT_FOUND, "Grant not found", null, request);
        }
        DaiPrincipal caller = gate.caller();
        audit.record(caller, AuditCategory.ADMIN, AuditPlane.CONTROL, "GRANT_REVOKED", workspaceId, "grant",
                grantId.toString(), null, null,
                Map.of("permission", grant.permission(), "grantee", grant.principalId().toString()));
        return ResponseEntity.noContent().build();
    }

    private static @Nullable GrantTarget target(CreateRequest body, List<FieldViolation> errors) {
        String type = body.targetType() == null || body.targetType().isBlank() ? "WORKSPACE"
                : body.targetType().strip().toUpperCase(Locale.ROOT);
        switch (type) {
            case "WORKSPACE" -> {
                if (body.resourceId() != null || body.pattern() != null) {
                    errors.add(new FieldViolation("targetType", "WORKSPACE takes no resourceId or pattern"));
                    return null;
                }
                return new GrantTarget.WorkspaceWide();
            }
            case "RESOURCE" -> {
                if (body.resourceId() == null) {
                    errors.add(new FieldViolation("resourceId", "is required for RESOURCE"));
                    return null;
                }
                return new GrantTarget.OnResource(body.resourceId());
            }
            case "PATTERN" -> {
                String pattern = AdminApi.text(errors, "pattern", body.pattern(), true, MAX_PATTERN);
                return pattern == null ? null : new GrantTarget.OnPattern(pattern);
            }
            default -> {
                errors.add(new FieldViolation("targetType", "must be WORKSPACE, RESOURCE or PATTERN"));
                return null;
            }
        }
    }
}
