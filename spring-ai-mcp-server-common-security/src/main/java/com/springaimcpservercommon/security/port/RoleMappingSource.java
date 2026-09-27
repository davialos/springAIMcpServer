package com.springaimcpservercommon.security.port;

import com.springaimcpservercommon.security.principal.RoleMappingRule;

import java.util.List;

/**
 * Port to the enabled rows of {@code dai_role_mapping} (mappings maintained in the dashboard, SEC-01 §3).
 * Static bootstrap mappings from configuration are passed to the mapper separately.
 */
public interface RoleMappingSource {

    /**
     * Returns all enabled mappings. Called on principal-mapping cache misses; implementations should serve it from a
     * short-lived cache refreshed on change (the table is small and changes rarely).
     *
     * @return enabled mappings
     */
    List<RoleMappingRule> findEnabledMappings();
}
