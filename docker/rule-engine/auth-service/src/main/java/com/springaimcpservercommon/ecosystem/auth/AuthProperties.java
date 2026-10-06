package com.springaimcpservercommon.ecosystem.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Settings of the auth service.
 *
 * @param jwtSecret     HMAC key that signs access tokens (at least 32 bytes)
 * @param tokenTtl      token lifetime
 * @param maxFailures   failed logins of one user inside {@code lockoutWindow} before further attempts are refused
 * @param lockoutWindow window the failures are counted in
 * @param seed          dev seed users
 */
@ConfigurationProperties(prefix = "ecosystem.auth")
public record AuthProperties(String jwtSecret, Duration tokenTtl, int maxFailures, Duration lockoutWindow, Seed seed) {

    /** Validates that the signing key is long enough: a short key would make the tokens forgeable. */
    public AuthProperties {
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("ecosystem.auth.jwt-secret must be at least 32 bytes (set JWT_SECRET)");
        }
        if (maxFailures < 1) {
            throw new IllegalStateException("ecosystem.auth.max-failures must be positive");
        }
    }

    /**
     * Dev seed.
     *
     * @param enabled       create the tenants, organizations and users on startup when missing
     * @param adminPassword password of the seeded admins
     * @param userPassword  password of the seeded users
     */
    public record Seed(boolean enabled, String adminPassword, String userPassword) {
    }
}
