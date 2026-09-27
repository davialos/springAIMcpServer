package com.springaimcpservercommon.security.principal;

import com.springaimcpservercommon.core.hash.Sha256;
import com.springaimcpservercommon.core.principal.SubjectType;
import com.springaimcpservercommon.security.internal.TtlCache;
import com.springaimcpservercommon.security.port.PrincipalDirectoryPort;

import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Resolves the {@code dai_principal} ids of a principal's IdP groups (lookup only, never creates rows), with a short
 * bounded cache. Shared by the mapper (workspace memberships) and the authorization engine (grant subjects), so both
 * see the same group identities.
 */
public final class GroupPrincipalIds {

    private final PrincipalDirectoryPort directory;
    private final TtlCache<String, Set<UUID>> cache;

    /**
     * Creates the resolver.
     *
     * @param directory  principal directory port
     * @param clock      time source
     * @param ttl        cache lifetime (≤ 5 minutes recommended)
     * @param maxEntries cache bound
     */
    public GroupPrincipalIds(PrincipalDirectoryPort directory, Clock clock, Duration ttl, int maxEntries) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.cache = new TtlCache<>(maxEntries, ttl, clock);
    }

    /**
     * Returns the principal ids of the given groups that exist in the store.
     *
     * @param issuer issuer the groups belong to
     * @param groups external group ids
     * @return principal ids (possibly empty)
     */
    public Set<UUID> resolve(String issuer, Set<String> groups) {
        if (groups.isEmpty()) {
            return Set.of();
        }
        String key = Sha256.of(issuer + '\n' + String.join("\n", new TreeSet<>(groups)));
        return cache.get(key).orElseGet(() -> {
            Set<UUID> ids = Set.copyOf(new HashSet<>(directory.findPrincipalIds(SubjectType.GROUP, issuer, groups).values()));
            cache.put(key, ids);
            return ids;
        });
    }

    /** Drops all cached lookups (e.g. after membership or grant changes that created group rows). */
    public void invalidateAll() {
        cache.invalidateAll();
    }
}
