package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.environment.Capability;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.persistence.audit.AuditCategory;
import com.springaimcpservercommon.persistence.audit.AuditPlane;
import com.springaimcpservercommon.persistence.config.ClusterStatus;
import com.springaimcpservercommon.persistence.config.ConfigStore;
import com.springaimcpservercommon.persistence.config.PublishResult;
import com.springaimcpservercommon.security.permission.Permission;
import com.springaimcpservercommon.webmvc.problem.ProblemDetailFactory.FieldViolation;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Cluster status API (LLD-08 §2): which nodes are alive and which published generation each has applied.
 * Requires {@link Permission#OPS_KILLSWITCH} (the operator role; a dedicated {@code ops:read} permission
 * is tracked in the open questions).
 *
 * <p>Not a {@code @Component}; registered by {@link DaiAdminAutoConfiguration}.
 */
@NullMarked
@RequestMapping("/dynamic-ai/admin/api/v1/cluster")
public final class ClusterAdminController {

    static final int DEFAULT_ALIVE_SECONDS = 60;
    static final int MIN_ALIVE_SECONDS = 5;
    static final int MAX_ALIVE_SECONDS = 3600;

    /**
     * Cluster snapshot.
     *
     * @param latestGeneration newest published generation, or {@code null} when nothing is published
     * @param converged        whether every alive node has applied the latest generation
     * @param aliveWithinSeconds heartbeat window used to decide liveness
     * @param nodes            known nodes
     */
    public record ClusterView(@Nullable Long latestGeneration, boolean converged, int aliveWithinSeconds,
                              List<ClusterStatus.Node> nodes) {}

    /**
     * Rollback request.
     *
     * @param reason why the installation is rolled back (mandatory, up to 500 characters)
     */
    public record RollbackRequest(@Nullable String reason) {}

    private final ConfigStore configStore;
    private final AdminAudit audit;
    private final AdminApi api;

    private final Runnable generationPublished;

    ClusterAdminController(ConfigStore configStore, AdminAudit audit, AdminApi api) {
        this(configStore, audit, api, () -> { });
    }

    /**
     * @param generationPublished called after a rollback published a new generation on this node
     */
    ClusterAdminController(ConfigStore configStore, AdminAudit audit, AdminApi api, Runnable generationPublished) {
        this.generationPublished = Objects.requireNonNull(generationPublished, "generationPublished");
        this.configStore = Objects.requireNonNull(configStore, "configStore");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.api = Objects.requireNonNull(api, "api");
    }

    /**
     * Nodes with their applied generation.
     *
     * @param aliveWithinSeconds heartbeat window, 5..3600 (default 60)
     * @param request            current request
     * @return 200 with the cluster view
     */
    @GetMapping("/nodes")
    public ResponseEntity<?> nodes(@RequestParam(required = false) @Nullable Integer aliveWithinSeconds,
                                   HttpServletRequest request) {
        var gate = api.gate(request, Permission.OPS_KILLSWITCH, null);
        if (!gate.open()) {
            return gate.denied();
        }
        int window = aliveWithinSeconds == null ? DEFAULT_ALIVE_SECONDS : aliveWithinSeconds;
        if (window < MIN_ALIVE_SECONDS || window > MAX_ALIVE_SECONDS) {
            throw new IllegalArgumentException("aliveWithinSeconds must be between " + MIN_ALIVE_SECONDS + " and "
                    + MAX_ALIVE_SECONDS);
        }
        ClusterStatus status = configStore.clusterStatus(Duration.ofSeconds(window));
        return ResponseEntity.ok(new ClusterView(status.latestGeneration(), status.converged(), window,
                status.nodes()));
    }

    /**
     * Rolls the whole installation back to an earlier published generation (LLD-09 §2.7). This republishes the
     * live set of that generation as a new generation; it affects every workspace, so it needs a global
     * {@link Permission#WORKSPACE_ADMIN} and the {@link Capability#CONFIG_CHANGES_UI} capability.
     *
     * @param generation generation to restore
     * @param body       reason (mandatory)
     * @param request    current request
     * @return 200 with the new generation; 409 when the generation is unknown or cannot be restored
     */
    @PostMapping("/generations/{generation:[0-9]+}:rollback")
    public ResponseEntity<?> rollback(@PathVariable long generation, @RequestBody @Nullable RollbackRequest body,
                                      HttpServletRequest request) {
        var gate = api.gate(request, Permission.WORKSPACE_ADMIN, null);
        if (!gate.open()) {
            return gate.denied();
        }
        ResponseEntity<String> disabled = api.capabilityDenied(Capability.CONFIG_CHANGES_UI, request);
        if (disabled != null) {
            return disabled;
        }
        List<FieldViolation> errors = new ArrayList<>();
        String reason = AdminApi.text(errors, "reason", body == null ? null : body.reason(), true, 500);
        if (!errors.isEmpty()) {
            return AdminApi.validation(request, errors);
        }
        DaiPrincipal caller = gate.caller();
        PublishResult result = configStore.rollback(generation, caller.principalId(), Objects.requireNonNull(reason));
        audit.record(caller, AuditCategory.ADMIN, AuditPlane.CONTROL, "GENERATION_ROLLED_BACK", null, "generation",
                Long.toString(generation), null, reason, Map.of("newGeneration", result.generation()));
        generationPublished.run();
        return ResponseEntity.ok(result);
    }
}
