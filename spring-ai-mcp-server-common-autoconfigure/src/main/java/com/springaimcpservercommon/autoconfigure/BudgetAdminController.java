package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.persistence.audit.AuditCategory;
import com.springaimcpservercommon.persistence.audit.AuditPlane;
import com.springaimcpservercommon.persistence.usage.BudgetLimits;
import com.springaimcpservercommon.persistence.usage.BudgetPeriod;
import com.springaimcpservercommon.persistence.usage.BudgetStore;
import com.springaimcpservercommon.persistence.usage.BudgetTarget;
import com.springaimcpservercommon.persistence.usage.BudgetView;
import com.springaimcpservercommon.persistence.usage.UsageLedger;
import com.springaimcpservercommon.persistence.usage.UsageTotals;
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
 * Token and cost budget admin API (F-70). Requires {@link Permission#BUDGET_MANAGE}.
 *
 * <p>Two route families share the handlers: {@code /workspaces/{workspaceId}/budgets} for workspace, agent
 * and workspace-scoped principal budgets (permission checked in that workspace), and {@code /budgets} for
 * global budgets and principal budgets that span workspaces (permission checked globally). A budget is only
 * reachable through the route family that matches its target, so a workspace owner cannot read or change
 * global budgets.
 *
 * <p>Money is {@code long} micros plus an ISO-4217 code. Limit changes need {@code If-Match}. Not a
 * {@code @Component}; registered by {@link DaiAdminAutoConfiguration}.
 */
@NullMarked
@RequestMapping("/dynamic-ai/admin/api/v1")
public final class BudgetAdminController {

    static final long MAX_LIMIT = 1_000_000_000_000_000L;

    /**
     * Create request.
     *
     * @param scope          workspace routes: WORKSPACE (default), AGENT, PRINCIPAL; global routes: GLOBAL
     *                       (default), PRINCIPAL
     * @param agentResourceId required for AGENT
     * @param principalId    required for PRINCIPAL
     * @param period         DAY or MONTH (UTC)
     * @param limits         limits
     */
    public record CreateRequest(@Nullable String scope, @Nullable UUID agentResourceId, @Nullable UUID principalId,
                                @Nullable String period, @Nullable LimitsRequest limits) {}

    /**
     * Limits.
     *
     * @param limitTokens     token limit, positive, optional if a cost limit is set
     * @param limitCostMicros cost limit in currency micros, positive, optional if a token limit is set
     * @param currency        ISO-4217 code; required exactly when a cost limit is set
     * @param softLimitPct    alert threshold 1..100 (default 80)
     * @param hardLimit       whether reaching the limit blocks calls (default true)
     */
    public record LimitsRequest(@Nullable Long limitTokens, @Nullable Long limitCostMicros,
                                @Nullable String currency, @Nullable Integer softLimitPct,
                                @Nullable Boolean hardLimit) {}

    /**
     * Usage in the budget's current period.
     *
     * @param calls              model calls
     * @param totalTokens        all tokens
     * @param costMicros         cost in the budget's currency, when it has a cost limit
     * @param percentUsed        highest used share of any limit, 0 or more (may exceed 100)
     */
    public record UsageDto(long calls, long totalTokens, @Nullable Long costMicros, double percentUsed) {}

    /**
     * Budget as shown to the UI.
     *
     * @param id              id
     * @param scope           GLOBAL, WORKSPACE, AGENT or PRINCIPAL
     * @param workspaceId     workspace, if any
     * @param agentResourceId agent, if any
     * @param principalId     principal, if any
     * @param period          DAY or MONTH
     * @param limitTokens     token limit
     * @param limitCostMicros cost limit micros
     * @param currency        currency
     * @param softLimitPct    soft threshold
     * @param hardLimit       hard limit
     * @param enabled         whether in force
     * @param createdAt       creation time
     * @param updatedAt       last update
     * @param version         row version (also the ETag)
     * @param usage           current period usage; only on the detail endpoint
     */
    public record BudgetDto(UUID id, String scope, @Nullable UUID workspaceId, @Nullable UUID agentResourceId,
                            @Nullable UUID principalId, String period, @Nullable Long limitTokens,
                            @Nullable Long limitCostMicros, @Nullable String currency, int softLimitPct,
                            boolean hardLimit, boolean enabled, Instant createdAt, Instant updatedAt, long version,
                            @Nullable UsageDto usage) {
        static BudgetDto of(BudgetView b, @Nullable UsageDto usage) {
            UUID ws = workspaceOf(b.target());
            UUID agent = b.target() instanceof BudgetTarget.Agent a ? a.agentResourceId() : null;
            UUID principal = b.target() instanceof BudgetTarget.Principal p ? p.principalId() : null;
            BudgetLimits l = b.limits();
            return new BudgetDto(b.id(), b.target().scope().name(), ws, agent, principal, b.period().name(),
                    l.limitTokens(), l.limitCostMicros(), l.currency(), l.softLimitPct(), l.hardLimit(),
                    b.enabled(), b.createdAt(), b.updatedAt(), b.rowVersion(), usage);
        }
    }

    private final BudgetStore store;
    private final UsageLedger ledger;
    private final AdminAudit audit;
    private final AdminApi api;

    BudgetAdminController(BudgetStore store, UsageLedger ledger, AdminAudit audit, AdminApi api) {
        this.store = Objects.requireNonNull(store, "store");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.api = Objects.requireNonNull(api, "api");
    }

    /**
     * Lists the budgets of the route's scope family.
     *
     * @param workspaceId workspace for the workspace route family; absent for global budgets
     * @param request     current request
     * @return 200 with budgets (without usage)
     */
    @GetMapping({"/budgets", "/workspaces/{workspaceId}/budgets"})
    public ResponseEntity<?> list(@PathVariable(required = false) @Nullable UUID workspaceId,
                                  HttpServletRequest request) {
        var gate = api.gate(request, Permission.BUDGET_MANAGE, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        return ResponseEntity.ok(store.list().stream().filter(b -> inFamily(b, workspaceId))
                .map(b -> BudgetDto.of(b, null)).toList());
    }

    /**
     * Returns one budget with its current-period usage.
     *
     * @param workspaceId workspace for the workspace route family
     * @param id          budget id
     * @param request     current request
     * @return 200 with an ETag; 404 when unknown or not in this route family
     */
    @GetMapping({"/budgets/{id}", "/workspaces/{workspaceId}/budgets/{id}"})
    public ResponseEntity<?> get(@PathVariable(required = false) @Nullable UUID workspaceId, @PathVariable UUID id,
                                 HttpServletRequest request) {
        var gate = api.gate(request, Permission.BUDGET_MANAGE, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        BudgetView b = find(id, workspaceId);
        if (b == null) {
            return AdminApi.problem(ProblemCode.NOT_FOUND, "Budget not found", null, request);
        }
        return etag(HttpStatus.OK, b, usageOf(b));
    }

    /**
     * Creates a budget.
     *
     * @param workspaceId workspace for the workspace route family
     * @param body        target, period and limits
     * @param request     current request
     * @return 201 with the budget; 400 with field errors
     */
    @PostMapping({"/budgets", "/workspaces/{workspaceId}/budgets"})
    public ResponseEntity<?> create(@PathVariable(required = false) @Nullable UUID workspaceId,
                                    @RequestBody CreateRequest body, HttpServletRequest request) {
        var gate = api.gate(request, Permission.BUDGET_MANAGE, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        List<FieldViolation> errors = new ArrayList<>();
        BudgetTarget target = target(body, workspaceId, errors);
        BudgetPeriod period = null;
        if (body.period() == null || body.period().isBlank()) {
            errors.add(new FieldViolation("period", "is required (DAY or MONTH)"));
        } else {
            try {
                period = BudgetPeriod.valueOf(body.period().strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                errors.add(new FieldViolation("period", "must be DAY or MONTH"));
            }
        }
        BudgetLimits limits = limits(body.limits(), errors);
        if (!errors.isEmpty() || target == null || period == null || limits == null) {
            return AdminApi.validation(request, errors);
        }
        DaiPrincipal caller = gate.caller();
        BudgetView created = store.create(target, period, limits, caller.principalId());
        record(caller, "BUDGET_CREATED", created);
        return etag(HttpStatus.CREATED, created, null);
    }

    /**
     * Replaces the limits of a budget. Requires {@code If-Match} with the row version last read.
     *
     * @param workspaceId workspace for the workspace route family
     * @param id          budget id
     * @param ifMatch     row version
     * @param body        new limits
     * @param request     current request
     * @return 200 with the budget; 412 when stale; 428 when {@code If-Match} is missing
     */
    @PutMapping({"/budgets/{id}/limits", "/workspaces/{workspaceId}/budgets/{id}/limits"})
    public ResponseEntity<?> changeLimits(@PathVariable(required = false) @Nullable UUID workspaceId,
                                          @PathVariable UUID id,
                                          @RequestHeader(value = "If-Match", required = false) @Nullable String ifMatch,
                                          @RequestBody LimitsRequest body, HttpServletRequest request) {
        var gate = api.gate(request, Permission.BUDGET_MANAGE, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        Long version = AdminApi.ifMatch(ifMatch);
        if (version == null) {
            return AdminApi.problem(ProblemCode.PRECONDITION_REQUIRED, "If-Match required",
                    "Send the row version you last read in If-Match.", request);
        }
        if (find(id, workspaceId) == null) {
            return AdminApi.problem(ProblemCode.NOT_FOUND, "Budget not found", null, request);
        }
        List<FieldViolation> errors = new ArrayList<>();
        BudgetLimits limits = limits(body, errors);
        if (!errors.isEmpty() || limits == null) {
            return AdminApi.validation(request, errors);
        }
        DaiPrincipal caller = gate.caller();
        BudgetView updated = store.changeLimits(id, version, limits, caller.principalId());
        record(caller, "BUDGET_LIMITS_CHANGED", updated);
        return etag(HttpStatus.OK, updated, null);
    }

    /**
     * Enables a budget.
     *
     * @param workspaceId workspace for the workspace route family
     * @param id          budget id
     * @param request     current request
     * @return 200 with the budget
     */
    @PostMapping({"/budgets/{id:[^:]+}:enable", "/workspaces/{workspaceId}/budgets/{id:[^:]+}:enable"})
    public ResponseEntity<?> enable(@PathVariable(required = false) @Nullable UUID workspaceId,
                                    @PathVariable UUID id, HttpServletRequest request) {
        return setEnabled(workspaceId, id, true, request);
    }

    /**
     * Disables a budget without deleting it.
     *
     * @param workspaceId workspace for the workspace route family
     * @param id          budget id
     * @param request     current request
     * @return 200 with the budget
     */
    @PostMapping({"/budgets/{id:[^:]+}:disable", "/workspaces/{workspaceId}/budgets/{id:[^:]+}:disable"})
    public ResponseEntity<?> disable(@PathVariable(required = false) @Nullable UUID workspaceId,
                                     @PathVariable UUID id, HttpServletRequest request) {
        return setEnabled(workspaceId, id, false, request);
    }

    /**
     * Deletes a budget.
     *
     * @param workspaceId workspace for the workspace route family
     * @param id          budget id
     * @param request     current request
     * @return 204 when deleted
     */
    @DeleteMapping({"/budgets/{id}", "/workspaces/{workspaceId}/budgets/{id}"})
    public ResponseEntity<?> delete(@PathVariable(required = false) @Nullable UUID workspaceId,
                                    @PathVariable UUID id, HttpServletRequest request) {
        var gate = api.gate(request, Permission.BUDGET_MANAGE, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        BudgetView current = find(id, workspaceId);
        if (current == null) {
            return AdminApi.problem(ProblemCode.NOT_FOUND, "Budget not found", null, request);
        }
        store.delete(id);
        record(gate.caller(), "BUDGET_DELETED", current);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<?> setEnabled(@Nullable UUID workspaceId, UUID id, boolean enabled,
                                         HttpServletRequest request) {
        var gate = api.gate(request, Permission.BUDGET_MANAGE, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        if (find(id, workspaceId) == null) {
            return AdminApi.problem(ProblemCode.NOT_FOUND, "Budget not found", null, request);
        }
        DaiPrincipal caller = gate.caller();
        BudgetView updated = store.setEnabled(id, enabled, caller.principalId());
        record(caller, enabled ? "BUDGET_ENABLED" : "BUDGET_DISABLED", updated);
        return etag(HttpStatus.OK, updated, null);
    }

    private @Nullable BudgetView find(UUID id, @Nullable UUID workspaceId) {
        return store.find(id).filter(b -> inFamily(b, workspaceId)).orElse(null);
    }

    /** Workspace routes see budgets of that workspace; global routes see GLOBAL and cross-workspace principal budgets. */
    private static boolean inFamily(BudgetView b, @Nullable UUID workspaceId) {
        UUID ws = workspaceOf(b.target());
        return workspaceId == null ? ws == null : workspaceId.equals(ws);
    }

    private static @Nullable UUID workspaceOf(BudgetTarget target) {
        return switch (target) {
            case BudgetTarget.Global _ -> null;
            case BudgetTarget.Workspace w -> w.workspaceId();
            case BudgetTarget.Agent a -> a.workspaceId();
            case BudgetTarget.Principal p -> p.workspaceId();
        };
    }

    private @Nullable UsageDto usageOf(BudgetView b) {
        UsageTotals totals = ledger.currentPeriodTotals(b);
        BudgetLimits l = b.limits();
        double percent = 0;
        if (l.limitTokens() != null) {
            percent = Math.max(percent, 100.0 * totals.totalTokens() / l.limitTokens());
        }
        Long cost = null;
        if (l.limitCostMicros() != null && l.currency() != null) {
            cost = totals.costMicros(l.currency());
            percent = Math.max(percent, 100.0 * cost / l.limitCostMicros());
        }
        return new UsageDto(totals.calls(), totals.totalTokens(), cost, percent);
    }

    private static @Nullable BudgetTarget target(CreateRequest body, @Nullable UUID workspaceId,
                                                 List<FieldViolation> errors) {
        String defaultScope = workspaceId == null ? "GLOBAL" : "WORKSPACE";
        String raw = body.scope() == null || body.scope().isBlank() ? defaultScope
                : body.scope().strip().toUpperCase(Locale.ROOT);
        BudgetTarget.Scope scope;
        try {
            scope = BudgetTarget.Scope.valueOf(raw);
        } catch (IllegalArgumentException e) {
            errors.add(new FieldViolation("scope", "must be GLOBAL, WORKSPACE, AGENT or PRINCIPAL"));
            return null;
        }
        boolean workspaceRoute = workspaceId != null;
        switch (scope) {
            case GLOBAL -> {
                if (workspaceRoute) {
                    errors.add(new FieldViolation("scope", "GLOBAL budgets are managed under /budgets"));
                    return null;
                }
                return new BudgetTarget.Global();
            }
            case WORKSPACE -> {
                if (!workspaceRoute) {
                    errors.add(new FieldViolation("scope", "WORKSPACE budgets are managed under /workspaces/{id}/budgets"));
                    return null;
                }
                return new BudgetTarget.Workspace(workspaceId);
            }
            case AGENT -> {
                if (!workspaceRoute) {
                    errors.add(new FieldViolation("scope", "AGENT budgets are managed under /workspaces/{id}/budgets"));
                    return null;
                }
                if (body.agentResourceId() == null) {
                    errors.add(new FieldViolation("agentResourceId", "is required for AGENT"));
                    return null;
                }
                return new BudgetTarget.Agent(workspaceId, body.agentResourceId());
            }
            case PRINCIPAL -> {
                if (body.principalId() == null) {
                    errors.add(new FieldViolation("principalId", "is required for PRINCIPAL"));
                    return null;
                }
                return new BudgetTarget.Principal(workspaceId, body.principalId());
            }
        }
        return null;
    }

    static @Nullable BudgetLimits limits(@Nullable LimitsRequest in, List<FieldViolation> errors) {
        if (in == null) {
            errors.add(new FieldViolation("limits", "is required"));
            return null;
        }
        int before = errors.size();
        if (in.limitTokens() == null && in.limitCostMicros() == null) {
            errors.add(new FieldViolation("limits", "needs limitTokens or limitCostMicros"));
        }
        if (in.limitTokens() != null && (in.limitTokens() <= 0 || in.limitTokens() > MAX_LIMIT)) {
            errors.add(new FieldViolation("limitTokens", "must be between 1 and " + MAX_LIMIT));
        }
        if (in.limitCostMicros() != null && (in.limitCostMicros() <= 0 || in.limitCostMicros() > MAX_LIMIT)) {
            errors.add(new FieldViolation("limitCostMicros", "must be between 1 and " + MAX_LIMIT));
        }
        String currency = in.currency() == null || in.currency().isBlank() ? null
                : in.currency().strip().toUpperCase(Locale.ROOT);
        if ((in.limitCostMicros() == null) != (currency == null)) {
            errors.add(new FieldViolation("currency", "is required exactly when limitCostMicros is set"));
        } else if (currency != null && !currency.matches("[A-Z]{3}")) {
            errors.add(new FieldViolation("currency", "must be an ISO-4217 code like EUR"));
        }
        int soft = in.softLimitPct() == null ? BudgetLimits.DEFAULT_SOFT_LIMIT_PCT : in.softLimitPct();
        if (soft < 1 || soft > 100) {
            errors.add(new FieldViolation("softLimitPct", "must be between 1 and 100"));
        }
        if (errors.size() > before) {
            return null;
        }
        return new BudgetLimits(in.limitTokens(), in.limitCostMicros(), currency, soft,
                in.hardLimit() == null || in.hardLimit());
    }

    private void record(DaiPrincipal caller, String action, BudgetView b) {
        audit.record(caller, AuditCategory.ADMIN, AuditPlane.CONTROL, action, workspaceOf(b.target()), "budget",
                b.id().toString(), null, null,
                Map.of("scope", b.target().scope().name(), "period", b.period().name(),
                        "hardLimit", b.limits().hardLimit()));
    }

    private static ResponseEntity<BudgetDto> etag(HttpStatus status, BudgetView b, @Nullable UsageDto usage) {
        return ResponseEntity.status(status).eTag("\"" + b.rowVersion() + "\"").body(BudgetDto.of(b, usage));
    }
}
