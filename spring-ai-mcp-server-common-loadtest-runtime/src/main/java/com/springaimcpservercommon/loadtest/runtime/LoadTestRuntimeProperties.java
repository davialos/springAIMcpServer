package com.springaimcpservercommon.loadtest.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Settings of the runtime discovery endpoint ({@code loadtest.runtime.*}).
 *
 * @param enabled         whether {@code /actuator/loadtest} exists at all (default {@code false}: it describes the
 *                        whole API surface, so a host switches it on for development and test environments only)
 * @param allowProduction expose it although a {@code prod}/{@code production} profile is active (default {@code false})
 * @param excludePackages handler classes in these packages are left out (default: Spring's and the API-doc libraries')
 * @param excludePaths    route prefixes that are left out
 */
@ConfigurationProperties(prefix = "loadtest.runtime")
public record LoadTestRuntimeProperties(boolean enabled, boolean allowProduction, List<String> excludePackages,
                                        List<String> excludePaths) {

    /** Defaults. */
    public LoadTestRuntimeProperties {
        excludePackages = excludePackages == null || excludePackages.isEmpty()
                ? List.of("org.springframework.", "org.springdoc.", "springfox.", "io.swagger.") : List.copyOf(excludePackages);
        excludePaths = excludePaths == null || excludePaths.isEmpty() ? List.of("/error", "/actuator") : List.copyOf(excludePaths);
    }
}
