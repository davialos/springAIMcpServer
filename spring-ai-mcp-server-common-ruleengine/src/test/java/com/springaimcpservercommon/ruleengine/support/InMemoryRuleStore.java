package com.springaimcpservercommon.ruleengine.support;

import com.springaimcpservercommon.ruleengine.model.Parameter;
import com.springaimcpservercommon.ruleengine.store.RuleStore;
import com.springaimcpservercommon.ruleengine.store.Scope;
import com.springaimcpservercommon.ruleengine.store.TenantData;
import com.springaimcpservercommon.ruleengine.store.Versioned;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Mutable in-memory store with change markers, a load counter and a failure switch, for unit tests. */
public final class InMemoryRuleStore implements RuleStore {

    private final Map<Scope, Long> markers = new EnumMap<>(Scope.class);
    public volatile List<Parameter> parameters = SampleTenant.parameters();
    public volatile Map<UUID, Map<String, String>> messages = SampleTenant.messages();
    public volatile TenantData tenant = SampleTenant.tenantData();
    public volatile boolean failing;
    public final AtomicInteger tenantLoads = new AtomicInteger();

    public InMemoryRuleStore() {
        for (Scope s : Scope.values()) {
            markers.put(s, 1L);
        }
    }

    public synchronized void bump(Scope scope) {
        markers.merge(scope, 1L, Long::sum);
    }

    private void check() {
        if (failing) {
            throw new IllegalStateException("store down");
        }
    }

    @Override
    public synchronized long changeMarker(Scope scope) {
        check();
        return markers.get(scope);
    }

    @Override
    public synchronized Versioned<List<Parameter>> loadParameters() {
        check();
        return new Versioned<>(markers.get(Scope.PARAMETERS), parameters);
    }

    @Override
    public synchronized Versioned<Map<UUID, Map<String, String>>> loadMessages() {
        check();
        return new Versioned<>(markers.get(Scope.BUNDLES), messages);
    }

    @Override
    public synchronized Versioned<TenantData> loadTenant(UUID tenantId) {
        check();
        tenantLoads.incrementAndGet();
        return new Versioned<>(markers.get(Scope.RULES), tenantId.equals(SampleTenant.TENANT) ? tenant
                : new TenantData(tenantId, List.of(), List.of(), List.of(), Map.of(), Map.of()));
    }
}
