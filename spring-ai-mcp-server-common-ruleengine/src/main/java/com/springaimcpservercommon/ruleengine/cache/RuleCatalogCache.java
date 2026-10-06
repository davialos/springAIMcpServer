package com.springaimcpservercommon.ruleengine.cache;

import com.springaimcpservercommon.ruleengine.cel.ParameterLibrary;
import com.springaimcpservercommon.ruleengine.store.RuleStore;
import com.springaimcpservercommon.ruleengine.store.Scope;
import com.springaimcpservercommon.ruleengine.store.Versioned;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The cache of the rule engine: the parameter library (sys objects + attributes as {@code object.attribute} CEL
 * variables), the messages and each tenant's rules, all held as immutable snapshots in this node's memory.
 *
 * <p><b>Freshness without sticky state (ADR-0021).</b> Writers bump a row of {@code dai_re_change_marker} (database
 * triggers). Each node, at most once per {@code pollInterval} per tenant, reads the three marker rows and rebuilds
 * only what moved. N replicas converge within one poll interval; nothing is replicated between nodes.
 *
 * <p><b>Fail the feature, not the host.</b> If a refresh fails the last good snapshot keeps being served and the
 * error is logged; only a tenant that has never loaded throws {@link RuleCatalogUnavailableException}.
 */
public final class RuleCatalogCache {

    private static final Logger log = LoggerFactory.getLogger(RuleCatalogCache.class);

    private record TenantEntry(long parametersVersion, long messagesVersion, long rulesVersion,
                               TenantCatalog catalog, Instant checkedAt) {
    }

    private record ParametersEntry(long version, ParameterLibrary library) {
    }

    private record MessagesEntry(long version, MessageCatalog messages) {
    }

    private final RuleStore store;
    private final Clock clock;
    private final Duration pollInterval;
    private final int maxTenants;
    private final String defaultLanguage;
    private final Map<UUID, TenantEntry> tenants = new ConcurrentHashMap<>();
    private final Object refreshLock = new Object();
    private volatile ParametersEntry parameters;
    private volatile MessagesEntry messages;

    /**
     * Creates the cache.
     *
     * @param store           the store to load from
     * @param clock           time source
     * @param pollInterval    how often a node checks the change markers (per tenant)
     * @param maxTenants      most tenant snapshots kept; the least recently checked is dropped beyond it
     * @param defaultLanguage fallback message language, e.g. {@code en}
     */
    public RuleCatalogCache(RuleStore store, Clock clock, Duration pollInterval, int maxTenants,
                            String defaultLanguage) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        this.maxTenants = maxTenants;
        this.defaultLanguage = Objects.requireNonNull(defaultLanguage, "defaultLanguage");
    }

    /**
     * The current snapshot of a tenant, refreshed if its change markers moved.
     *
     * @param tenantId tenant
     * @return the snapshot (possibly slightly stale if the store is down)
     * @throws RuleCatalogUnavailableException if nothing was ever loaded for the tenant and the store fails
     */
    public TenantCatalog catalog(UUID tenantId) {
        Instant now = clock.instant();
        TenantEntry entry = tenants.get(tenantId);
        if (entry != null && Duration.between(entry.checkedAt(), now).compareTo(pollInterval) < 0) {
            return entry.catalog();
        }
        synchronized (refreshLock) {
            entry = tenants.get(tenantId);
            now = clock.instant();
            if (entry != null && Duration.between(entry.checkedAt(), now).compareTo(pollInterval) < 0) {
                return entry.catalog();
            }
            try {
                return refresh(tenantId, entry, now);
            } catch (RuntimeException e) {
                if (entry != null) {
                    log.warn("rule catalog refresh failed for tenant {}; serving the previous snapshot: {}",
                            tenantId, e.getMessage());
                    tenants.put(tenantId, new TenantEntry(entry.parametersVersion(), entry.messagesVersion(),
                            entry.rulesVersion(), entry.catalog(), now));
                    return entry.catalog();
                }
                throw new RuleCatalogUnavailableException("rule catalog unavailable for tenant " + tenantId, e);
            }
        }
    }

    /**
     * The current parameter library (what expressions are checked against), refreshed when its change marker moved.
     * Used by the authoring services so an author sees a new parameter as soon as any node has loaded it.
     *
     * @return the library
     * @throws RuleCatalogUnavailableException if it was never loaded and the store fails
     */
    public ParameterLibrary library() {
        synchronized (refreshLock) {
            try {
                long pv = store.changeMarker(Scope.PARAMETERS);
                ParametersEntry p = parameters;
                if (p == null || p.version() != pv) {
                    Versioned<java.util.List<com.springaimcpservercommon.ruleengine.model.Parameter>> loaded = store.loadParameters();
                    p = new ParametersEntry(loaded.version(), new ParameterLibrary(loaded.value()));
                    parameters = p;
                }
                return p.library();
            } catch (RuntimeException e) {
                ParametersEntry last = parameters;
                if (last != null) {
                    return last.library();
                }
                throw new RuleCatalogUnavailableException("parameter library unavailable", e);
            }
        }
    }

    /** Drops every snapshot so the next call reloads (admin "refresh now"; local, other nodes follow the markers). */
    public void invalidate() {
        synchronized (refreshLock) {
            tenants.clear();
            parameters = null;
            messages = null;
        }
    }

    private TenantCatalog refresh(UUID tenantId, TenantEntry entry, Instant now) {
        long pv = store.changeMarker(Scope.PARAMETERS);
        long mv = store.changeMarker(Scope.BUNDLES);
        long rv = store.changeMarker(Scope.RULES);
        if (entry != null && entry.parametersVersion() == pv && entry.messagesVersion() == mv
                && entry.rulesVersion() == rv) {
            tenants.put(tenantId, new TenantEntry(pv, mv, rv, entry.catalog(), now));
            return entry.catalog();
        }
        ParametersEntry p = parameters;
        if (p == null || p.version() != pv) {
            Versioned<java.util.List<com.springaimcpservercommon.ruleengine.model.Parameter>> loaded =
                    store.loadParameters();
            p = new ParametersEntry(loaded.version(), new ParameterLibrary(loaded.value()));
            parameters = p;
        }
        MessagesEntry m = messages;
        if (m == null || m.version() != mv) {
            Versioned<Map<UUID, Map<String, String>>> loaded = store.loadMessages();
            m = new MessagesEntry(loaded.version(), new MessageCatalog(loaded.value(), defaultLanguage));
            messages = m;
        }
        Versioned<com.springaimcpservercommon.ruleengine.store.TenantData> data = store.loadTenant(tenantId);
        TenantCatalog catalog = new TenantCatalog(data.value(), p.library(), m.messages());
        // label the snapshot with the versions it was actually loaded at: a write in between is picked up next poll
        tenants.put(tenantId, new TenantEntry(p.version(), m.version(), data.version(), catalog, now));
        evictBeyondLimit();
        return catalog;
    }

    private void evictBeyondLimit() {
        while (tenants.size() > maxTenants) {
            tenants.entrySet().stream().min(Comparator.comparing(e -> e.getValue().checkedAt()))
                    .ifPresent(e -> tenants.remove(e.getKey()));
        }
    }
}
