package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.model.ModelRouter;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.persistence.audit.AuditCategory;
import com.springaimcpservercommon.persistence.audit.AuditPlane;
import com.springaimcpservercommon.security.permission.Permission;
import com.springaimcpservercommon.webmvc.problem.ProblemCode;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Model provider status API (OQ-47): which providers this node can call and the state of each provider's circuit
 * breaker, plus a manual reset. Breakers are per node (ADR-0021), so this answers for the node that serves the
 * request; the response names that node. Requires {@link Permission#OPS_KILLSWITCH}, like the cluster view.
 *
 * <p>Not a {@code @Component}; registered by {@link DaiAdminAutoConfiguration}.
 */
@NullMarked
@RequestMapping("/dynamic-ai/admin/api/v1/model-providers")
public final class ModelProviderAdminController {

    /**
     * Provider status of one node.
     *
     * @param node      the node that answered (host name)
     * @param available {@code false} when a custom {@link ModelRouter} without breakers is in use
     * @param providers providers with their breaker state
     */
    public record ProvidersView(String node, boolean available, List<Provider> providers) {}

    /**
     * One provider.
     *
     * @param provider            normalized provider id
     * @param state               {@code CLOSED}, {@code OPEN} or {@code HALF_OPEN}
     * @param consecutiveFailures failures since the last success
     * @param openedAt            when the breaker opened, if open
     * @param retryAt             when an open breaker admits its probe, if open
     */
    public record Provider(String provider, String state, int consecutiveFailures,
                           java.time.@Nullable Instant openedAt, java.time.@Nullable Instant retryAt) {}

    private final ObjectProvider<ModelRouter> router;
    private final AdminAudit audit;
    private final AdminApi api;
    private final String node;

    ModelProviderAdminController(ObjectProvider<ModelRouter> router, AdminAudit audit, AdminApi api, String node) {
        this.router = Objects.requireNonNull(router, "router");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.api = Objects.requireNonNull(api, "api");
        this.node = Objects.requireNonNull(node, "node");
    }

    /**
     * Lists the providers of this node.
     *
     * @param request current request
     * @return 200 with the providers
     */
    @GetMapping
    public ResponseEntity<?> providers(HttpServletRequest request) {
        var gate = api.gate(request, Permission.OPS_KILLSWITCH, null);
        if (!gate.open()) {
            return gate.denied();
        }
        if (!(router.getIfAvailable() instanceof DefaultModelRouter defaults)) {
            return ResponseEntity.ok(new ProvidersView(node, false, List.of()));
        }
        List<Provider> providers = defaults.providers().stream()
                .map(p -> new Provider(p.provider(), p.breaker().state(), p.breaker().consecutiveFailures(),
                        p.breaker().openedAt(), p.breaker().retryAt()))
                .toList();
        return ResponseEntity.ok(new ProvidersView(node, true, providers));
    }

    /**
     * Closes a provider's breaker on this node, for an operator who knows the provider has recovered. Audited.
     *
     * @param provider provider id
     * @param request  current request
     * @return 204 when reset, 404 when the provider is unknown on this node
     */
    @PostMapping("/{provider:[^:]+}:reset-breaker")
    public ResponseEntity<?> resetBreaker(@PathVariable String provider, HttpServletRequest request) {
        var gate = api.gate(request, Permission.OPS_KILLSWITCH, null);
        if (!gate.open()) {
            return gate.denied();
        }
        if (!(router.getIfAvailable() instanceof DefaultModelRouter defaults) || !defaults.resetBreaker(provider)) {
            return AdminApi.problem(ProblemCode.NOT_FOUND, "Model provider not found", null, request);
        }
        DaiPrincipal caller = gate.caller();
        audit.record(caller, AuditCategory.ADMIN, AuditPlane.CONTROL, "MODEL_BREAKER_RESET", null,
                "model_provider", DefaultModelRouter.normalize(provider), null, null, Map.of("node", node));
        return ResponseEntity.noContent().build();
    }
}
