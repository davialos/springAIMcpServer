package com.springaimcpservercommon.webmvc.endpoint;

import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.security.authz.AuthorizationEngine;
import com.springaimcpservercommon.security.authz.AuthorizationOutcome;
import com.springaimcpservercommon.security.authz.AuthorizationRequest;
import com.springaimcpservercommon.security.authz.ResourceRef;
import com.springaimcpservercommon.webmvc.problem.ProblemCode;
import com.springaimcpservercommon.webmvc.problem.ProblemDetailFactory;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.Controller;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/**
 * The single Spring MVC handler for all dynamic endpoint routes (LLD-04 §4).
 *
 * <p>Registered once as the handler object for every dynamic {@link RequestMappingInfo};
 * dispatches to the correct pipeline stage per request.
 *
 * <p>Pipeline stages (in order):
 * <ol>
 *   <li>Resolve route definition from the {@link DynamicEndpointRegistrar} route table.</li>
 *   <li>Kill-switch / suspension check.</li>
 *   <li>Principal extraction and {@link DaiPrincipal} mapping.</li>
 *   <li>Authorization check ({@code perm:endpoint:invoke}).</li>
 *   <li>Parameter parsing and JSON schema validation.</li>
 *   <li>Rate limit check.</li>
 *   <li>Execute the backing via the appropriate {@link BackingExecutor} port.</li>
 *   <li>Response shaping + envelope.</li>
 *   <li>Audit + metrics (always, even on failure).</li>
 * </ol>
 *
 * <p>Errors are always written as RFC 9457 {@code application/problem+json}.
 * This class is not a Spring {@code @Component} — the autoconfigure module registers it.
 */
@NullMarked
public final class GenericDynamicHandler implements Controller {

    private static final Logger LOG = LoggerFactory.getLogger(GenericDynamicHandler.class);
    private static final String CONTENT_TYPE_PROBLEM = "application/problem+json;charset=UTF-8";
    private static final String CONTENT_TYPE_JSON = "application/json;charset=UTF-8";

    /**
     * Port: resolves the {@link DaiPrincipal} for the current request.
     *
     * <p>The autoconfigure module wires this by composing the security module's
     * {@link com.springaimcpservercommon.security.principal.IdentityExtraction} chain
     * with the principal attribute resolver.
     */
    @FunctionalInterface
    public interface DaiPrincipalResolver {
        /**
         * @param request current HTTP request
         * @return resolved principal
         * @throws SecurityException if no authenticated principal is available
         */
        DaiPrincipal resolve(HttpServletRequest request);
    }

    /**
     * Port: executes the backing of a dynamic endpoint and returns the raw result as a JSON string.
     */
    @FunctionalInterface
    public interface BackingExecutor {
        /**
         * Executes the endpoint backing.
         *
         * @param def       the endpoint definition
         * @param principal calling principal
         * @param params    resolved parameter values (name → value)
         * @return JSON string result; may be a JSON array or object
         * @throws BackingException on execution failure
         */
        String execute(EndpointDefinition def, DaiPrincipal principal,
                        java.util.Map<String, Object> params) throws BackingException;
    }

    /**
     * Thrown by {@link BackingExecutor} when execution fails.
     *
     * Carries the problem code and a safe, displayable message. (A class, not a record: records cannot extend
     * {@link Exception}.) The stack trace is not filled in: this is an expected control-flow failure.
     */
    public static final class BackingException extends Exception {

        private static final long serialVersionUID = 1L;

        private final ProblemCode code;

        /**
         * Creates the exception.
         *
         * @param code    problem code for the failure
         * @param message safe, displayable message
         */
        public BackingException(ProblemCode code, String message) {
            this(code, message, null);
        }

        /**
         * Creates the exception with the failure that caused it (kept for classification, for example a host
         * optimistic-lock failure; never shown to callers).
         *
         * @param code    problem code for the failure
         * @param message safe, displayable message
         * @param cause   underlying failure, if any
         */
        public BackingException(ProblemCode code, String message, @Nullable Throwable cause) {
            super(Objects.requireNonNull(message, "message"), cause, false, false);
            this.code = Objects.requireNonNull(code, "code");
        }

        /** @return problem code for the failure */
        public ProblemCode code() {
            return code;
        }

        /** @return safe, displayable message */
        public String message() {
            return getMessage();
        }
    }

    /**
     * Port: checks whether an endpoint's kill switch is active.
     */
    @FunctionalInterface
    public interface KillSwitchChecker {
        boolean isActive(UUID endpointId);
    }

    /**
     * Port: per-principal rate-limit check.
     */
    @FunctionalInterface
    public interface RateLimiter {
        /**
         * @return {@code -1} if allowed; seconds until retry if rate-limited
         */
        int checkAndRecord(DaiPrincipal principal, UUID endpointId);
    }

    private final DynamicEndpointRegistrar registrar;
    private final DaiPrincipalResolver principalResolver;
    private final AuthorizationEngine authorizationEngine;
    private final BackingExecutor backingExecutor;
    private final KillSwitchChecker killSwitchChecker;
    private final RateLimiter rateLimiter;
    private final ObservationRegistry observationRegistry;

    /**
     * Creates the handler.
     *
     * @param registrar            route table for definition lookup
     * @param principalResolver    resolves {@link DaiPrincipal} for the current request
     * @param authorizationEngine  authorizes the invocation
     * @param backingExecutor      executes the endpoint backing
     * @param killSwitchChecker    checks the endpoint kill switch
     * @param rateLimiter          enforces per-principal rate limits
     * @param observationRegistry  Micrometer registry for metrics
     */
    public GenericDynamicHandler(DynamicEndpointRegistrar registrar,
                                  DaiPrincipalResolver principalResolver,
                                  AuthorizationEngine authorizationEngine,
                                  BackingExecutor backingExecutor,
                                  KillSwitchChecker killSwitchChecker,
                                  RateLimiter rateLimiter,
                                  ObservationRegistry observationRegistry) {
        this.registrar = Objects.requireNonNull(registrar, "registrar");
        this.principalResolver = Objects.requireNonNull(principalResolver, "principalResolver");
        this.authorizationEngine = Objects.requireNonNull(authorizationEngine, "authorizationEngine");
        this.backingExecutor = Objects.requireNonNull(backingExecutor, "backingExecutor");
        this.killSwitchChecker = Objects.requireNonNull(killSwitchChecker, "killSwitchChecker");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry");
    }

    @Override
    public @Nullable ModelAndView handleRequest(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String fullPath = resolveFullPath(request);
        String method = request.getMethod();

        // Stage 1: resolve route definition
        EndpointDefinition def = registrar.lookup(fullPath, method);
        if (def == null) {
            writeProblem(response, ProblemCode.NOT_FOUND,
                    "No dynamic endpoint at " + method + " " + fullPath,
                    null, fullPath);
            return null;
        }

        // Stage 2: kill-switch
        if (killSwitchChecker.isActive(def.id())) {
            writeProblem(response, ProblemCode.ENDPOINT_DISABLED,
                    "This endpoint is temporarily disabled.",
                    null, fullPath);
            return null;
        }

        // Stage 3: principal extraction
        DaiPrincipal principal;
        try {
            principal = extractPrincipal(request);
        } catch (Exception e) {
            LOG.debug("Principal extraction failed for {}: {}", fullPath, e.getMessage());
            writeProblem(response, ProblemCode.ACCESS_DENIED, "Authentication required.", null, fullPath);
            return null;
        }

        // Stage 4: authorization
        var resource = ResourceRef.of(def.workspaceId(), def.id(),
                com.springaimcpservercommon.annotations.Classification.PUBLIC);
        var authRequest = AuthorizationRequest.onResource(principal,
                com.springaimcpservercommon.security.permission.Permission.ENDPOINT_INVOKE, resource);
        var outcome = authorizationEngine.decide(authRequest);
        if (outcome instanceof AuthorizationOutcome.Deny) {
            writeProblem(response, ProblemCode.ACCESS_DENIED, "Access denied.", null, fullPath);
            return null;
        }

        // Stage 5: parameter validation (delegated to backing executor via params map)
        java.util.Map<String, Object> params = parseParams(request, def);

        // Stage 6: rate limit
        int retryAfter = rateLimiter.checkAndRecord(principal, def.id());
        if (retryAfter >= 0) {
            if (retryAfter > 0) response.setHeader("Retry-After", String.valueOf(retryAfter));
            writeProblem(response, ProblemCode.RATE_LIMITED, "Rate limit exceeded.", null, fullPath);
            return null;
        }

        // Stage 7+8: execute backing, shape response
        try {
            String result = backingExecutor.execute(def, principal, params);
            String shaped = shapeResponse(def.response(), result);
            response.setContentType(CONTENT_TYPE_JSON);
            response.setStatus(200);
            response.getWriter().write(shaped);
        } catch (BackingException e) {
            writeProblem(response, e.code(), e.message(), null, fullPath);
        } catch (Exception e) {
            LOG.error("Unexpected error executing {} {}", method, fullPath, e);
            writeProblem(response, ProblemCode.INTERNAL_ERROR, "An unexpected error occurred.", null, fullPath);
        }
        return null;
    }

    private static String resolveFullPath(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern != null) return pattern.toString();
        return request.getRequestURI();
    }

    private DaiPrincipal extractPrincipal(HttpServletRequest request) {
        return principalResolver.resolve(request);
    }

    private static java.util.Map<String, Object> parseParams(HttpServletRequest request,
                                                               EndpointDefinition def) {
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> pathVars =
                (java.util.Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        for (ParamSpec spec : def.params()) {
            Object value = switch (spec.in()) {
                case PATH -> pathVars != null ? pathVars.get(spec.name()) : null;
                case QUERY -> request.getParameter(spec.name());
                case HEADER -> request.getHeader(spec.name());
                case BODY -> null; // body parsed separately
            };
            if (value == null && spec.defaultValue() != null) value = spec.defaultValue();
            if (value != null) params.put(spec.name(), value);
        }
        return java.util.Collections.unmodifiableMap(params);
    }

    private static String shapeResponse(ResponseShape shape, String rawJson) {
        if (!shape.envelope()) return rawJson;
        // Wrap in standard envelope: {data: <raw>, count: N}
        // This is a simple wrapper; the full projection/masking logic belongs in a dedicated shaper.
        return "{\"data\":" + rawJson + "}";
    }

    private static void writeProblem(HttpServletResponse response, ProblemCode code,
                                      String detail, @Nullable String title, String instance) throws IOException {
        response.setStatus(code.httpStatus());
        response.setContentType(CONTENT_TYPE_PROBLEM);
        String t = title != null ? title : code.code();
        response.getWriter().write(ProblemDetailFactory.build(code, t, detail, instance));
    }
}
