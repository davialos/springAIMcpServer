package com.springaimcpservercommon.ruleengine.store;

import com.springaimcpservercommon.ruleengine.model.Parameter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read port of the rule engine. The default implementation is {@link JdbcRuleStore}; a host may supply its own
 * (another database, a config service). All methods are called off the request path by the cache, except
 * {@link #changeMarker(Scope)} which is a single-row read.
 */
public interface RuleStore {

    /**
     * Cheap change counter for a scope; the cache reloads when it moves.
     *
     * @param scope scope
     * @return current version
     */
    long changeMarker(Scope scope);

    /**
     * Loads the whole parameter library (active objects and attributes).
     *
     * @return parameters and the marker version they were read at
     */
    Versioned<List<Parameter>> loadParameters();

    /**
     * Loads every message: bundle id, then language tag, then text.
     *
     * @return messages and the marker version they were read at
     */
    Versioned<Map<UUID, Map<String, String>>> loadMessages();

    /**
     * Loads one tenant's active rules, groups, triggers and communication configuration.
     *
     * @param tenantId tenant
     * @return the tenant's data and the marker version it was read at
     */
    Versioned<TenantData> loadTenant(UUID tenantId);
}
