package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.persistence.config.ClusterStatus;
import com.springaimcpservercommon.persistence.config.ConfigStore;
import com.springaimcpservercommon.security.permission.Permission;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Duration;
import java.util.List;
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

    private final ConfigStore configStore;
    private final AdminApi api;

    ClusterAdminController(ConfigStore configStore, AdminApi api) {
        this.configStore = Objects.requireNonNull(configStore, "configStore");
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
}
