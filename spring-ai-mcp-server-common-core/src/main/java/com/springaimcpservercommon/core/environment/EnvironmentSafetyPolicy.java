package com.springaimcpservercommon.core.environment;

/**
 * Strategy deciding the environment tier and which capabilities exist in it (LLD-12 §2). Hosts may replace the
 * default ({@code @ConditionalOnMissingBean}). Evaluated at startup (bean conditions) and again per request
 * (defence in depth, and so that an override expires without a restart).
 */
public interface EnvironmentSafetyPolicy {

    /**
     * Resolves the environment identity.
     *
     * @param signals configured tier, active profiles, ids
     * @return the identity
     */
    EnvironmentIdentity identify(EnvironmentSignals signals);

    /**
     * Whether a capability is enabled right now.
     *
     * @param capability the capability
     * @param identity   resolved identity
     * @return {@code true} if enabled
     */
    boolean isEnabled(Capability capability, EnvironmentIdentity identity);
}
