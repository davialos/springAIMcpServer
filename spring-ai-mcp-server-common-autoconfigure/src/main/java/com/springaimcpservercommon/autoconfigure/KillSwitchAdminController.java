package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.persistence.audit.AuditCategory;
import com.springaimcpservercommon.persistence.audit.AuditPlane;
import com.springaimcpservercommon.persistence.config.KillSwitchStore;
import com.springaimcpservercommon.persistence.config.KillSwitchTarget;
import com.springaimcpservercommon.persistence.config.KillSwitchView;
import com.springaimcpservercommon.security.permission.Permission;
import com.springaimcpservercommon.webmvc.problem.ProblemCode;
import com.springaimcpservercommon.webmvc.problem.ProblemDetailFactory;
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
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Kill switch admin API (F-73): list, set and clear switches. Requires {@link Permission#OPS_KILLSWITCH}
 * (workspace-scoped when the switch targets a workspace, global otherwise). Every change is written to the
 * audit trail after it is committed.
 *
 * <p>Not a {@code @Component}; registered by {@link DaiAdminAutoConfiguration}.
 */
@NullMarked
@RequestMapping("/dynamic-ai/admin/api/v1/kill-switches")
public final class KillSwitchAdminController {

    static final int MAX_REASON_LENGTH = 500;
    static final Duration MAX_EXPIRY = Duration.ofDays(365);
    static final Duration DEFAULT_HISTORY = Duration.ofDays(7);
    static final Duration MAX_HISTORY = Duration.ofDays(90);

    /**
     * Request body for setting a kill switch.
     *
     * @param scope       {@code GLOBAL}, {@code WORKSPACE}, {@code RESOURCE} or {@code TOOL}
     * @param workspaceId required for {@code WORKSPACE}; optional narrowing for {@code RESOURCE} and {@code TOOL}
     * @param resourceId  required for {@code RESOURCE}
     * @param toolName    required for {@code TOOL} (snake_case tool name)
     * @param reason      mandatory human reason, 1..500 characters
     * @param expiresAt   optional ISO-8601 expiry, in the future and within one year
     */
    public record SetRequest(
            @Nullable String scope,
            @Nullable UUID workspaceId,
            @Nullable UUID resourceId,
            @Nullable String toolName,
            @Nullable String reason,
            @Nullable String expiresAt) {}

    /**
     * Kill switch as shown to the UI.
     *
     * @param id          switch id
     * @param scope       target scope
     * @param workspaceId target workspace, if any
     * @param resourceId  target resource, if any
     * @param toolName    target tool, if any
     * @param reason      reason given when set
     * @param setBy       principal that set it
     * @param setAt       when it was set
     * @param expiresAt   automatic expiry, if any
     * @param clearedBy   principal that cleared it, if any
     * @param clearedAt   when it was cleared, if any
     * @param active      whether it is in force now
     */
    public record SwitchView(UUID id, String scope, @Nullable UUID workspaceId, @Nullable UUID resourceId,
                             @Nullable String toolName, String reason, UUID setBy, Instant setAt,
                             @Nullable Instant expiresAt, @Nullable UUID clearedBy, @Nullable Instant clearedAt,
                             boolean active) {}

    private final KillSwitchStore store;
    private final AdminAudit audit;
    private final AdminApi api;
    private final Clock clock;

    KillSwitchAdminController(KillSwitchStore store, AdminAudit audit, AdminApi api, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.api = Objects.requireNonNull(api, "api");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Switches in force now. Visible to anyone holding the permission globally or in at least the
     * requested scope; workspace-scoped operators only see switches of their own workspace via
     * {@code workspaceId}.
     *
     * @param workspaceId optional workspace filter; when set the caller is checked against that workspace
     * @param request     current request
     * @return 200 with the active switches
     */
    @GetMapping
    public ResponseEntity<?> listActive(@RequestParam(required = false) @Nullable UUID workspaceId,
                                        HttpServletRequest request) {
        var gate = api.gate(request, Permission.OPS_KILLSWITCH, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        Instant now = clock.instant();
        return ResponseEntity.ok(store.listActive().stream()
                .filter(k -> workspaceId == null || workspaceOf(k.target()) == null
                        || workspaceId.equals(workspaceOf(k.target())))
                .map(k -> view(k, now)).toList());
    }

    /**
     * Switches set since a point in time, including cleared and expired ones.
     *
     * @param since   ISO-8601 instant; default 7 days ago, at most 90 days back
     * @param request current request
     * @return 200 with the history
     */
    @GetMapping("/history")
    public ResponseEntity<?> history(@RequestParam(required = false) @Nullable String since,
                                     HttpServletRequest request) {
        var gate = api.gate(request, Permission.OPS_KILLSWITCH, null);
        if (!gate.open()) {
            return gate.denied();
        }
        Instant now = clock.instant();
        Instant from = since == null || since.isBlank() ? now.minus(DEFAULT_HISTORY) : AdminApi.instant(since, "since");
        if (from.isAfter(now) || from.isBefore(now.minus(MAX_HISTORY))) {
            throw new IllegalArgumentException("since must be within the last " + MAX_HISTORY.toDays() + " days");
        }
        return ResponseEntity.ok(store.history(from).stream().map(k -> view(k, now)).toList());
    }

    /**
     * Sets a kill switch.
     *
     * @param body    what to disable and why
     * @param request current request
     * @return 201 with the new switch; 400 with field errors; 401/403
     */
    @PostMapping
    public ResponseEntity<?> set(@RequestBody SetRequest body, HttpServletRequest request) {
        List<FieldViolation> errors = new ArrayList<>();
        KillSwitchTarget target = parseTarget(body, errors);
        String reason = body.reason() == null ? "" : body.reason().strip();
        if (reason.isEmpty() || reason.length() > MAX_REASON_LENGTH) {
            errors.add(new FieldViolation("reason", "is required and must be at most " + MAX_REASON_LENGTH
                    + " characters"));
        }
        Instant now = clock.instant();
        Instant expiresAt = null;
        if (body.expiresAt() != null && !body.expiresAt().isBlank()) {
            try {
                expiresAt = AdminApi.instant(body.expiresAt(), "expiresAt");
                if (!expiresAt.isAfter(now) || expiresAt.isAfter(now.plus(MAX_EXPIRY))) {
                    errors.add(new FieldViolation("expiresAt", "must be in the future and within one year"));
                }
            } catch (IllegalArgumentException e) {
                errors.add(new FieldViolation("expiresAt", "must be an ISO-8601 instant"));
            }
        }
        // Authenticate and authorize before revealing validation detail about the target.
        var gate = api.gate(request, Permission.OPS_KILLSWITCH, target == null ? null : workspaceOf(target));
        if (!gate.open()) {
            return gate.denied();
        }
        if (!errors.isEmpty() || target == null) {
            return ResponseEntity.status(ProblemCode.INVALID_ARGUMENT.httpStatus())
                    .contentType(AdminApi.PROBLEM_JSON)
                    .body(ProblemDetailFactory.buildValidation(request.getRequestURI(), errors));
        }
        DaiPrincipal caller = gate.caller();
        KillSwitchView created = store.set(target, reason, caller.principalId(), expiresAt);
        audit(caller, "KILL_SWITCH_SET", created, reason);
        return ResponseEntity.status(HttpStatus.CREATED).body(view(created, now));
    }

    /**
     * Clears an active kill switch.
     *
     * @param id      switch id
     * @param request current request
     * @return 204 when cleared; 404 when the switch is unknown, already cleared or expired
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> clear(@PathVariable UUID id, HttpServletRequest request) {
        var authenticated = api.authenticated(request);
        if (!authenticated.open()) {
            return authenticated.denied();
        }
        KillSwitchView target = store.listActive().stream().filter(k -> k.id().equals(id)).findFirst().orElse(null);
        if (target == null) {
            var gate = api.gate(request, Permission.OPS_KILLSWITCH, null);
            return gate.open()
                    ? AdminApi.problem(ProblemCode.NOT_FOUND, "Kill switch not found", null, request)
                    : gate.denied();
        }
        var gate = api.gate(request, Permission.OPS_KILLSWITCH, workspaceOf(target.target()));
        if (!gate.open()) {
            return gate.denied();
        }
        DaiPrincipal caller = gate.caller();
        if (!store.clear(id, caller.principalId())) {
            return AdminApi.problem(ProblemCode.CONFLICT, "Kill switch already cleared", null, request);
        }
        audit(caller, "KILL_SWITCH_CLEARED", target, null);
        return ResponseEntity.noContent().build();
    }

    private static @Nullable KillSwitchTarget parseTarget(SetRequest body, List<FieldViolation> errors) {
        if (body.scope() == null || body.scope().isBlank()) {
            errors.add(new FieldViolation("scope", "is required (GLOBAL, WORKSPACE, RESOURCE or TOOL)"));
            return null;
        }
        KillSwitchTarget.Scope scope;
        try {
            scope = KillSwitchTarget.Scope.valueOf(body.scope().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add(new FieldViolation("scope", "must be GLOBAL, WORKSPACE, RESOURCE or TOOL"));
            return null;
        }
        switch (scope) {
            case GLOBAL -> {
                if (body.workspaceId() != null || body.resourceId() != null || body.toolName() != null) {
                    errors.add(new FieldViolation("scope", "GLOBAL takes no workspaceId, resourceId or toolName"));
                    return null;
                }
                return new KillSwitchTarget.Global();
            }
            case WORKSPACE -> {
                if (body.workspaceId() == null) {
                    errors.add(new FieldViolation("workspaceId", "is required for WORKSPACE"));
                    return null;
                }
                return new KillSwitchTarget.Workspace(body.workspaceId());
            }
            case RESOURCE -> {
                if (body.resourceId() == null) {
                    errors.add(new FieldViolation("resourceId", "is required for RESOURCE"));
                    return null;
                }
                return new KillSwitchTarget.Resource(body.workspaceId(), body.resourceId());
            }
            case TOOL -> {
                if (body.toolName() == null) {
                    errors.add(new FieldViolation("toolName", "is required for TOOL"));
                    return null;
                }
                try {
                    return new KillSwitchTarget.Tool(body.workspaceId(), body.toolName());
                } catch (IllegalArgumentException e) {
                    errors.add(new FieldViolation("toolName", "must match [a-z][a-z0-9_]{2,63}"));
                    return null;
                }
            }
        }
        return null;
    }

    private static @Nullable UUID workspaceOf(KillSwitchTarget target) {
        return switch (target) {
            case KillSwitchTarget.Global _ -> null;
            case KillSwitchTarget.Workspace w -> w.workspaceId();
            case KillSwitchTarget.Resource r -> r.workspaceId();
            case KillSwitchTarget.Tool t -> t.workspaceId();
        };
    }

    private static SwitchView view(KillSwitchView k, Instant now) {
        UUID resourceId = k.target() instanceof KillSwitchTarget.Resource r ? r.resourceId() : null;
        String toolName = k.target() instanceof KillSwitchTarget.Tool t ? t.toolName() : null;
        return new SwitchView(k.id(), k.target().scope().name(), workspaceOf(k.target()), resourceId, toolName,
                k.reason(), k.setBy(), k.setAt(), k.expiresAt(), k.clearedBy(), k.clearedAt(), k.activeAt(now));
    }

    private void audit(DaiPrincipal caller, String action, KillSwitchView sw, @Nullable String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("scope", sw.target().scope().name());
        if (sw.expiresAt() != null) {
            details.put("expiresAt", sw.expiresAt().toString());
        }
        audit.record(caller, AuditCategory.ADMIN, AuditPlane.CONTROL, action, workspaceOf(sw.target()),
                "kill_switch", sw.id().toString(), null, reason, details);
    }
}
