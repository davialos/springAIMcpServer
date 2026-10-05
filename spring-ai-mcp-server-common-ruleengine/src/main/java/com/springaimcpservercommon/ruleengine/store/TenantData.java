package com.springaimcpservercommon.ruleengine.store;

import com.springaimcpservercommon.ruleengine.model.ApiEndpoint;
import com.springaimcpservercommon.ruleengine.model.ChannelBinding;
import com.springaimcpservercommon.ruleengine.model.EmailTemplate;
import com.springaimcpservercommon.ruleengine.model.RuleGroup;
import com.springaimcpservercommon.ruleengine.model.TriggerPoint;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything one tenant owns, as loaded by {@link RuleStore#loadTenant(UUID)}.
 *
 * @param tenantId       tenant
 * @param groups         active groups with their enabled, active rules
 * @param triggers       enabled trigger points
 * @param channels       enabled channel bindings
 * @param emailTemplates active templates by id
 * @param apiEndpoints   active endpoints by id
 */
public record TenantData(UUID tenantId, List<RuleGroup> groups, List<TriggerPoint> triggers,
                         List<ChannelBinding> channels, Map<UUID, EmailTemplate> emailTemplates,
                         Map<UUID, ApiEndpoint> apiEndpoints) {

    /** Defensive copies. */
    public TenantData {
        groups = List.copyOf(groups);
        triggers = List.copyOf(triggers);
        channels = List.copyOf(channels);
        emailTemplates = Map.copyOf(emailTemplates);
        apiEndpoints = Map.copyOf(apiEndpoints);
    }
}
