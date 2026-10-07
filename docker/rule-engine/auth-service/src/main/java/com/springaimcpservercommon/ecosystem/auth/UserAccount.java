package com.springaimcpservercommon.ecosystem.auth;

import com.springaimcpservercommon.ruleengine.contract.v1.Role;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * A user with the tenant and organization he or she works for.
 *
 * @param id               user id
 * @param tenantId         tenant id
 * @param tenantName       tenant display name
 * @param organizationId   organization id, or {@code null} for a tenant-wide user
 * @param organizationName organization display name, or {@code null}
 * @param username         login name
 * @param displayName      display name
 * @param passwordHash     BCrypt hash
 * @param role             role
 * @param enabled          whether the user may sign in
 */
record UserAccount(UUID id, UUID tenantId, String tenantName, @Nullable UUID organizationId,
                   @Nullable String organizationName, String username, String displayName, String passwordHash,
                   Role role, boolean enabled) {
}
