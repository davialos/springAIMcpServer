package com.springaimcpservercommon.security.apikey;

import com.springaimcpservercommon.security.port.ApiKeyLookup;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Fakes for API key tests. */
final class ApiKeyTestSupport {

    private ApiKeyTestSupport() {
    }

    /** Peppers by version; version 1 and 2 exist, current is configurable. */
    static final class Peppers implements ApiKeyPepperProvider {
        int current = 1;
        final Map<Integer, byte[]> peppers = new HashMap<>(Map.of(
                1, "pepper-one-0123456789-0123456789-abcdef".getBytes(),
                2, "pepper-two-0123456789-0123456789-abcdef".getBytes()));

        @Override
        public int currentVersion() {
            return current;
        }

        @Override
        public byte[] pepper(int version) {
            byte[] pepper = peppers.get(version);
            if (pepper == null) {
                throw new IllegalArgumentException("unknown pepper version");
            }
            return pepper.clone();
        }
    }

    /** Key store keyed by prefix. */
    static final class Store implements ApiKeyLookup {
        final Map<String, ApiKeyRecord> keys = new HashMap<>();
        final List<UUID> touches = new ArrayList<>();
        int lookups;

        ApiKeyRecord save(GeneratedApiKey key, Set<String> permissions, List<String> networks) {
            ApiKeyRecord record = new ApiKeyRecord(UUID.randomUUID(), key.keyPrefix(), key.keyHash(), key.hashAlgorithm(),
                    key.expiresAt(), null, null, UUID.randomUUID(), UUID.randomUUID(), "etl-bot", true,
                    UUID.randomUUID(), permissions, networks);
            keys.put(key.keyPrefix(), record);
            return record;
        }

        void replace(ApiKeyRecord record) {
            keys.put(record.keyPrefix(), record);
        }

        @Override
        public Optional<ApiKeyRecord> findByPrefix(String keyPrefix) {
            lookups++;
            return Optional.ofNullable(keys.get(keyPrefix));
        }

        @Override
        public void touchLastUsed(UUID apiKeyId, Instant at) {
            touches.add(apiKeyId);
        }
    }

    static ApiKeyLookup.ApiKeyRecord revoked(ApiKeyLookup.ApiKeyRecord r, Instant at) {
        return new ApiKeyLookup.ApiKeyRecord(r.id(), r.keyPrefix(), r.keyHash(), r.hashAlgorithm(), r.expiresAt(), at,
                r.lastUsedAt(), r.serviceAccountId(), r.serviceAccountPrincipalId(), r.serviceAccountName(),
                r.serviceAccountActive(), r.workspaceId(), r.permissions(), r.allowedNetworks());
    }
}
