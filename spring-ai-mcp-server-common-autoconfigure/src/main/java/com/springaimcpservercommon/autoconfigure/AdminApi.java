package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.environment.Capability;
import com.springaimcpservercommon.core.environment.EnvironmentIdentity;
import com.springaimcpservercommon.core.environment.EnvironmentSafetyPolicy;
import com.springaimcpservercommon.core.environment.EnvironmentSignals;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.persistence.support.PageRequest;
import com.springaimcpservercommon.persistence.support.TimeRange;
import com.springaimcpservercommon.security.authz.AuthorizationEngine;
import com.springaimcpservercommon.security.authz.AuthorizationOutcome;
import com.springaimcpservercommon.security.authz.AuthorizationRequest;
import com.springaimcpservercommon.security.permission.Permission;
import com.springaimcpservercommon.webmvc.endpoint.GenericDynamicHandler;
import com.springaimcpservercommon.webmvc.problem.ProblemCode;
import com.springaimcpservercommon.webmvc.problem.ProblemDetailFactory;
import com.springaimcpservercommon.webmvc.problem.ProblemDetailFactory.FieldViolation;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Shared plumbing for the admin control-plane controllers: authentication and permission gate, RFC 9457
 * problem responses and bounded paging/time-window parsing (LLD-08 §2 conventions).
 *
 * <p>Not a Spring bean's public API; instantiated by {@link DaiAdminAutoConfiguration} and passed to the
 * controllers.
 */
@NullMarked
final class AdminApi {

    static final MediaType PROBLEM_JSON = MediaType.parseMediaType("application/problem+json;charset=UTF-8");

    /** Default page size for admin list endpoints. */
    static final int DEFAULT_LIMIT = 50;
    /** Largest page size an admin list endpoint returns. */
    static final int MAX_LIMIT = 200;
    /** Default look-back for time-windowed endpoints. */
    static final Duration DEFAULT_WINDOW = Duration.ofHours(24);

    private final GenericDynamicHandler.DaiPrincipalResolver principalResolver;
    private final AuthorizationEngine authorizationEngine;
    private final EnvironmentSafetyPolicy safetyPolicy;
    private final EnvironmentSignals environmentSignals;

    /**
     * Result of {@link #gate}: either the authenticated, authorized principal or a ready problem response.
     *
     * @param principal the caller when the gate passed, otherwise {@code null}
     * @param denied    the problem response when the gate failed, otherwise {@code null}
     */
    record Gate(@Nullable DaiPrincipal principal, @Nullable ResponseEntity<String> denied) {
        /** @return {@code true} when the caller may proceed */
        boolean open() {
            return denied == null;
        }

        /** @return the caller; only valid when {@link #open()} */
        DaiPrincipal caller() {
            return Objects.requireNonNull(principal, "gate is closed");
        }
    }

    AdminApi(GenericDynamicHandler.DaiPrincipalResolver principalResolver, AuthorizationEngine authorizationEngine,
             EnvironmentSafetyPolicy safetyPolicy, EnvironmentSignals environmentSignals) {
        this.principalResolver = Objects.requireNonNull(principalResolver, "principalResolver");
        this.authorizationEngine = Objects.requireNonNull(authorizationEngine, "authorizationEngine");
        this.safetyPolicy = Objects.requireNonNull(safetyPolicy, "safetyPolicy");
        this.environmentSignals = Objects.requireNonNull(environmentSignals, "environmentSignals");
    }

    /**
     * Checks that a capability is enabled in this environment (LLD-12 §2.2: authoring and introspection are off in
     * production, and an unknown tier counts as production).
     *
     * @param capability the capability the endpoint needs
     * @param request    current request
     * @return {@code null} when enabled, otherwise a ready 403 {@code capability-disabled} problem
     */
    @Nullable ResponseEntity<String> capabilityDenied(Capability capability, HttpServletRequest request) {
        EnvironmentIdentity identity = safetyPolicy.identify(environmentSignals);
        if (safetyPolicy.isEnabled(capability, identity)) {
            return null;
        }
        return problem(ProblemCode.CAPABILITY_DISABLED, "Capability disabled",
                capability.name() + " is not available in the " + identity.tier().name() + " environment.", request);
    }

    /**
     * Authenticates the caller and checks a permission. Unauthenticated callers get 401, unauthorized 403.
     *
     * @param request     current request
     * @param permission  required permission
     * @param workspaceId workspace scope, or {@code null} for a global check
     * @return the gate outcome
     */
    Gate gate(HttpServletRequest request, Permission permission, @Nullable UUID workspaceId) {
        DaiPrincipal principal;
        try {
            principal = principalResolver.resolve(request);
        } catch (RuntimeException e) {
            return new Gate(null, problem(ProblemCode.UNAUTHENTICATED, "Authentication required", null, request));
        }
        AuthorizationRequest authz = workspaceId == null
                ? AuthorizationRequest.global(principal, permission)
                : AuthorizationRequest.onWorkspace(principal, permission, workspaceId);
        if (authorizationEngine.decide(authz) instanceof AuthorizationOutcome.Deny) {
            return new Gate(null, problem(ProblemCode.ACCESS_DENIED, "Access denied", null, request));
        }
        return new Gate(principal, null);
    }

    /**
     * Authenticates the caller and requires at least one of several permissions.
     *
     * @param request     current request
     * @param workspaceId workspace scope, or {@code null} for global checks
     * @param permissions accepted permissions (any one suffices)
     * @return the gate outcome
     */
    Gate gateAny(HttpServletRequest request, @Nullable UUID workspaceId, Permission... permissions) {
        Gate authenticated = authenticated(request);
        if (!authenticated.open()) {
            return authenticated;
        }
        DaiPrincipal caller = authenticated.caller();
        for (Permission permission : permissions) {
            if (permits(caller, permission, workspaceId)) {
                return authenticated;
            }
        }
        return new Gate(null, problem(ProblemCode.ACCESS_DENIED, "Access denied", null, request));
    }

    /**
     * Checks a permission for an already authenticated caller without producing a response.
     *
     * @param principal   the caller
     * @param permission  permission to check
     * @param workspaceId workspace scope, or {@code null} for a global check
     * @return {@code true} if permitted
     */
    boolean permits(DaiPrincipal principal, Permission permission, @Nullable UUID workspaceId) {
        AuthorizationRequest authz = workspaceId == null
                ? AuthorizationRequest.global(principal, permission)
                : AuthorizationRequest.onWorkspace(principal, permission, workspaceId);
        return authorizationEngine.decide(authz) instanceof AuthorizationOutcome.Permit;
    }

    /**
     * Authenticates the caller without requiring a specific permission (for {@code /me}).
     *
     * @param request current request
     * @return the gate outcome
     */
    Gate authenticated(HttpServletRequest request) {
        try {
            return new Gate(principalResolver.resolve(request), null);
        } catch (RuntimeException e) {
            return new Gate(null, problem(ProblemCode.UNAUTHENTICATED, "Authentication required", null, request));
        }
    }

    /**
     * Builds an RFC 9457 problem response.
     *
     * @param code    problem code (drives type URI and status)
     * @param title   short title
     * @param detail  optional detail; must not contain secrets, prompts or row data
     * @param request current request (for {@code instance})
     * @return the response
     */
    static ResponseEntity<String> problem(ProblemCode code, String title, @Nullable String detail,
                                          HttpServletRequest request) {
        return ResponseEntity.status(code.httpStatus())
                .contentType(PROBLEM_JSON)
                .body(ProblemDetailFactory.build(code, title, detail, request.getRequestURI()));
    }

    /**
     * Parses a bounded page request.
     *
     * @param limit  requested size, or {@code null} for {@link #DEFAULT_LIMIT}
     * @param offset requested offset, or {@code null} for 0
     * @return the page request
     * @throws IllegalArgumentException if out of bounds
     */
    static PageRequest page(@Nullable Integer limit, @Nullable Integer offset) {
        int l = limit == null ? DEFAULT_LIMIT : limit;
        if (l > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT + ": " + l);
        }
        return new PageRequest(offset == null ? 0 : offset, l);
    }

    /**
     * Parses an ISO-8601 time window. Missing {@code to} defaults to now; missing {@code from} to
     * {@link #DEFAULT_WINDOW} before {@code to}.
     *
     * @param from ISO instant or {@code null}
     * @param to   ISO instant or {@code null}
     * @param now  current time
     * @return the validated range
     * @throws IllegalArgumentException if unparsable, inverted or wider than {@link TimeRange#MAX_SPAN}
     */
    static TimeRange window(@Nullable String from, @Nullable String to, Instant now) {
        Instant end = to == null || to.isBlank() ? now : instant(to, "to");
        Instant start = from == null || from.isBlank() ? end.minus(DEFAULT_WINDOW) : instant(from, "from");
        return new TimeRange(start, end);
    }

    /**
     * Parses an ISO-8601 instant.
     *
     * @param value raw text
     * @param name  parameter name for the error message
     * @return the instant
     * @throws IllegalArgumentException if unparsable
     */
    static Instant instant(String value, String name) {
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " must be an ISO-8601 instant, e.g. 2026-09-29T10:15:30Z");
        }
    }

    /** Parses {@code If-Match} as a row version; accepts {@code "7"}, {@code W/"7"} and {@code 7}. */
    static @Nullable Long ifMatch(@Nullable String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String v = header.strip();
        if (v.startsWith("W/")) {
            v = v.substring(2);
        }
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        try {
            long version = Long.parseLong(v);
            if (version < 0) {
                throw new NumberFormatException();
            }
            return version;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("If-Match must be a row version such as \"7\"");
        }
    }

    /**
     * Builds the 400 problem for a list of field violations.
     *
     * @param request    current request
     * @param violations violations, at least one
     * @return the response
     */
    static ResponseEntity<String> validation(HttpServletRequest request, List<FieldViolation> violations) {
        return ResponseEntity.status(ProblemCode.INVALID_ARGUMENT.httpStatus())
                .contentType(PROBLEM_JSON)
                .body(ProblemDetailFactory.buildValidation(request.getRequestURI(), violations));
    }

    /**
     * Validates a text field and returns its stripped value.
     *
     * @param errors   collector for violations
     * @param field    field name
     * @param value    raw value
     * @param required whether a non-blank value is mandatory
     * @param max      maximum length after stripping
     * @return the stripped value, or {@code null} when absent or invalid
     */
    static @Nullable String text(List<FieldViolation> errors, String field, @Nullable String value, boolean required,
                                 int max) {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty()) {
            if (required) {
                errors.add(new FieldViolation(field, "is required"));
            }
            return null;
        }
        if (v.length() > max) {
            errors.add(new FieldViolation(field, "must be at most " + max + " characters"));
            return null;
        }
        if (v.chars().anyMatch(Character::isISOControl)) {
            errors.add(new FieldViolation(field, "must not contain control characters"));
            return null;
        }
        return v;
    }
}
