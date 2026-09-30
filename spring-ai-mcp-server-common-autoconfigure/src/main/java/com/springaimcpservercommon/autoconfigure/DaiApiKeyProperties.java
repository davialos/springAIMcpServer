package com.springaimcpservercommon.autoconfigure;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Service-account API keys (SEC-01 §9, F-65), bound under {@code dynamic.ai.agent.security.api-keys}. Off by default:
 * machine clients then use the host's JWTs.
 *
 * @param enabled              accept {@code Authorization: ApiKey …} on the API and MCP planes and allow issuing keys
 * @param pepper               server-side secret for hashing keys, Base64, at least 32 bytes when decoded. Take it
 *                             from the host's secret store (environment variable, vault), never from a committed
 *                             file. Required when enabled; supply an {@code ApiKeyPepperProvider} bean instead for
 *                             rotation
 * @param pepperVersion        version recorded with new hashes (bump it when rotating the pepper)
 * @param keyEnvironment       label in the key text ({@code [a-z]{2,16}}); keys of another label are refused.
 *                             Defaults to the lower-cased environment tier
 * @param acceptDedicatedHeader also accept {@code X-DAI-Api-Key}
 * @param defaultLifetimeDays  lifetime of an issued key when the request names none (1..365)
 */
@ConfigurationProperties(prefix = "dynamic.ai.agent.security.api-keys")
public record DaiApiKeyProperties(
        @DefaultValue("false") boolean enabled,
        @Nullable String pepper,
        @DefaultValue("1") int pepperVersion,
        @Nullable String keyEnvironment,
        @DefaultValue("true") boolean acceptDedicatedHeader,
        @DefaultValue("90") int defaultLifetimeDays) {

    /** Validates the settings. */
    public DaiApiKeyProperties {
        if (pepperVersion < 1) {
            throw new IllegalArgumentException("dynamic.ai.agent.security.api-keys.pepper-version must be >= 1");
        }
        if (defaultLifetimeDays < 1 || defaultLifetimeDays > 365) {
            throw new IllegalArgumentException("dynamic.ai.agent.security.api-keys.default-lifetime-days must be 1..365");
        }
    }
}
