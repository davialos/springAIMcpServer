package com.springaimcpservercommon.core.environment;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Spring-free input for tier resolution, adapted from Spring's {@code Environment} by autoconfigure.
 *
 * @param explicitTier    value of {@code dynamic.ai.agent.environment.tier}, if set
 * @param activeProfiles  active Spring profiles
 * @param environmentId   value of {@code dynamic.ai.agent.environment.id}, if set
 * @param applicationName {@code spring.application.name}, if set
 */
public record EnvironmentSignals(@Nullable String explicitTier, List<String> activeProfiles,
                                 @Nullable String environmentId, @Nullable String applicationName) {

    /** Copies the profile list. */
    public EnvironmentSignals {
        activeProfiles = List.copyOf(activeProfiles);
    }
}
