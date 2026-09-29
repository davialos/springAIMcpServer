package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.tool.ToolBinding;
import com.springaimcpservercommon.persistence.config.ConfigStore;
import com.springaimcpservercommon.persistence.config.PublishedResource;
import com.springaimcpservercommon.persistence.config.PublishedSnapshot;
import com.springaimcpservercommon.persistence.config.ResourceKind;
import com.springaimcpservercommon.persistence.config.ResourceStatus;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The published tool bindings of the current generation, indexed by resource id and by workspace (MCP-exposed ones).
 * Rebuilt when a newer generation is published; a lookup re-checks the store at most every {@code maxAge}, and the
 * maintenance runner refreshes it in between ({@link DaiPersistenceAutoConfiguration.SnapshotView}). A binding whose
 * spec does not parse is skipped with a warning (no spec content is logged); suspended resources are not served.
 */
@NullMarked
final class ToolBindingSnapshotCache implements DaiPersistenceAutoConfiguration.SnapshotView {

    private static final Logger LOG = LoggerFactory.getLogger(ToolBindingSnapshotCache.class);

    private final ConfigStore configStore;
    private final long maxAgeNanos;
    private final AtomicLong loadedGeneration = new AtomicLong(0L);
    private final AtomicLong checkedAtNanos = new AtomicLong(System.nanoTime());
    private volatile boolean everChecked;
    private volatile Map<UUID, ToolBinding> byId = Map.of();
    private volatile Map<UUID, List<ToolBinding>> mcpExposedByWorkspace = Map.of();

    ToolBindingSnapshotCache(ConfigStore configStore, Duration maxAge) {
        this.configStore = Objects.requireNonNull(configStore, "configStore");
        this.maxAgeNanos = maxAge.toNanos();
    }

    @Nullable ToolBinding find(UUID bindingId) {
        refresh(false);
        return byId.get(bindingId);
    }

    List<ToolBinding> mcpExposed(UUID workspaceId) {
        refresh(false);
        return mcpExposedByWorkspace.getOrDefault(workspaceId, List.of());
    }

    @Override
    public void refreshNow() {
        refresh(true);
    }

    @Override
    public long loadedGeneration() {
        return loadedGeneration.get();
    }

    private void refresh(boolean force) {
        long now = System.nanoTime();
        if (!force && everChecked && now - checkedAtNanos.get() < maxAgeNanos) {
            return;
        }
        checkedAtNanos.set(now);
        everChecked = true;
        long latest = configStore.latestGeneration().orElse(0L);
        if (latest <= loadedGeneration.get()) {
            return;
        }
        synchronized (this) {
            if (latest <= loadedGeneration.get()) {
                return;
            }
            Optional<PublishedSnapshot> snapshot = configStore.loadSnapshot(latest);
            if (snapshot.isEmpty()) {
                return;
            }
            Map<UUID, ToolBinding> ids = new HashMap<>();
            Map<UUID, List<ToolBinding>> exposed = new HashMap<>();
            for (PublishedResource resource : snapshot.get().resources()) {
                if (resource.kind() != ResourceKind.TOOL_BINDING || resource.resourceStatus() == ResourceStatus.SUSPENDED) {
                    continue;
                }
                try {
                    ToolBinding binding = ToolBindingSpecs.parse(resource);
                    ids.put(binding.id(), binding);
                    if (binding.mcpExposed()) {
                        exposed.computeIfAbsent(binding.workspaceId(), w -> new ArrayList<>()).add(binding);
                    }
                } catch (RuntimeException e) {
                    LOG.warn("Tool binding resource {} (slug {}) skipped: {}", resource.resourceId(), resource.slug(),
                            e.getMessage());
                }
            }
            exposed.replaceAll((w, list) -> List.copyOf(list));
            byId = Map.copyOf(ids);
            mcpExposedByWorkspace = Map.copyOf(exposed);
            loadedGeneration.set(latest);
            LOG.debug("Tool binding snapshot refreshed: generation {}, {} bindings", latest, ids.size());
        }
    }
}
