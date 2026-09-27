package com.springaimcpservercommon.security.apikey;

import com.springaimcpservercommon.security.TestFixtures.MutableClock;
import com.springaimcpservercommon.security.apikey.ApiKeyVerification.Invalid;
import com.springaimcpservercommon.security.apikey.ApiKeyVerification.Reason;
import com.springaimcpservercommon.security.apikey.ApiKeyVerification.Valid;
import com.springaimcpservercommon.security.port.ApiKeyLookup.ApiKeyRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");

    private MutableClock clock;
    private ApiKeyTestSupport.Peppers peppers;
    private ApiKeyTestSupport.Store store;
    private ApiKeyService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        peppers = new ApiKeyTestSupport.Peppers();
        store = new ApiKeyTestSupport.Store();
        service = new ApiKeyService(store, peppers, "prod", clock, Duration.ZERO, Duration.ofMinutes(5));
    }

    private static Reason reason(ApiKeyVerification v) {
        assertThat(v).isInstanceOf(Invalid.class);
        return ((Invalid) v).reason();
    }

    @Test
    void generatedKeysHaveTheDocumentedFormat() {
        GeneratedApiKey key = service.generate(NOW.plus(Duration.ofDays(30)));
        assertThat(key.plaintext()).matches("^dai_prod_[A-Za-z0-9]{12}_[A-Za-z0-9_-]{43}$");
        assertThat(key.keyPrefix()).matches("^dai_[a-z]+_[A-Za-z0-9]{8,32}$");
        assertThat(key.plaintext()).startsWith(key.keyPrefix() + "_");
        assertThat(key.keyHash()).startsWith("v1:").doesNotContain(key.plaintext());
        assertThat(key.hashAlgorithm()).isEqualTo("hmac-sha256");
        assertThat(key.toString()).doesNotContain(key.plaintext());
        assertThat(service.generate(NOW.plusSeconds(60)).plaintext()).isNotEqualTo(key.plaintext());
    }

    @Test
    void expiryIsMandatoryAndBounded() {
        assertThatThrownBy(() -> service.generate(NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.generate(NOW.plus(Duration.ofDays(366)))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validKeyVerifiesAndTouchIsThrottled() {
        GeneratedApiKey key = service.generate(NOW.plus(Duration.ofDays(30)));
        ApiKeyRecord record = store.save(key, Set.of("tool:invoke"), List.of());

        assertThat(service.verify(key.plaintext(), null)).isEqualTo(new Valid(record));
        service.verify(key.plaintext(), null);
        assertThat(store.touches).hasSize(1);
        clock.advance(Duration.ofMinutes(6));
        service.verify(key.plaintext(), null);
        assertThat(store.touches).hasSize(2);
    }

    @Test
    void tamperedMalformedAndForeignKeysAreRejected() {
        GeneratedApiKey key = service.generate(NOW.plus(Duration.ofDays(30)));
        store.save(key, Set.of(), List.of());
        String text = key.plaintext();
        char last = text.charAt(text.length() - 1);
        String tampered = text.substring(0, text.length() - 1) + (last == 'A' ? 'B' : 'A');

        assertThat(reason(service.verify(tampered, null))).isEqualTo(Reason.MISMATCH);
        assertThat(reason(service.verify("dai_prod_short_x", null))).isEqualTo(Reason.MALFORMED);
        assertThat(reason(service.verify("Bearer " + text, null))).isEqualTo(Reason.MALFORMED);
        assertThat(reason(service.verify(text.replace("dai_prod_", "dai_stage_"), null))).isEqualTo(Reason.WRONG_ENVIRONMENT);
        assertThat(reason(service.verify(text.replace(key.keyPrefix(), "dai_prod_ZZZZZZZZZZZZ"), null)))
                .isEqualTo(Reason.UNKNOWN);
    }

    @Test
    void expiredRevokedAndDisabledKeysAreRejected() {
        GeneratedApiKey key = service.generate(NOW.plus(Duration.ofDays(1)));
        ApiKeyRecord record = store.save(key, Set.of(), List.of());

        clock.advance(Duration.ofDays(2));
        assertThat(reason(service.verify(key.plaintext(), null))).isEqualTo(Reason.EXPIRED);

        clock.set(NOW);
        store.replace(ApiKeyTestSupport.revoked(record, NOW.minusSeconds(1)));
        assertThat(reason(service.verify(key.plaintext(), null))).isEqualTo(Reason.REVOKED);

        store.replace(new ApiKeyRecord(record.id(), record.keyPrefix(), record.keyHash(), record.hashAlgorithm(),
                record.expiresAt(), null, null, record.serviceAccountId(), record.serviceAccountPrincipalId(),
                record.serviceAccountName(), false, record.workspaceId(), record.permissions(), record.allowedNetworks()));
        assertThat(reason(service.verify(key.plaintext(), null))).isEqualTo(Reason.DISABLED);
    }

    @Test
    void pepperRotationKeepsOldKeysValid() {
        GeneratedApiKey oldKey = service.generate(NOW.plus(Duration.ofDays(30)));
        store.save(oldKey, Set.of(), List.of());
        peppers.current = 2;
        GeneratedApiKey newKey = service.generate(NOW.plus(Duration.ofDays(30)));
        store.save(newKey, Set.of(), List.of());

        assertThat(newKey.keyHash()).startsWith("v2:");
        assertThat(service.verify(oldKey.plaintext(), null)).isInstanceOf(Valid.class);
        assertThat(service.verify(newKey.plaintext(), null)).isInstanceOf(Valid.class);

        peppers.peppers.remove(1);
        assertThat(reason(service.verify(oldKey.plaintext(), null))).isEqualTo(Reason.UNSUPPORTED_HASH);
    }

    @Test
    void allowedNetworksAreEnforced() throws Exception {
        GeneratedApiKey key = service.generate(NOW.plus(Duration.ofDays(30)));
        store.save(key, Set.of(), List.of("10.1.0.0/16", "2001:db8::/32"));

        assertThat(service.verify(key.plaintext(), InetAddress.getByName("10.1.2.3"))).isInstanceOf(Valid.class);
        assertThat(service.verify(key.plaintext(), InetAddress.getByName("2001:db8::1"))).isInstanceOf(Valid.class);
        assertThat(reason(service.verify(key.plaintext(), InetAddress.getByName("10.2.0.1")))).isEqualTo(Reason.NETWORK_NOT_ALLOWED);
        assertThat(reason(service.verify(key.plaintext(), null))).isEqualTo(Reason.NETWORK_NOT_ALLOWED);
    }

    @Test
    void recordCacheIsBoundedToThirtySeconds() {
        assertThatThrownBy(() -> new ApiKeyService(store, peppers, "prod", clock, Duration.ofSeconds(31), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        ApiKeyService cached = new ApiKeyService(store, peppers, "prod", clock, Duration.ofSeconds(30), Duration.ofMinutes(1));
        GeneratedApiKey key = cached.generate(NOW.plus(Duration.ofDays(30)));
        ApiKeyRecord record = store.save(key, Set.of(), List.of());
        cached.verify(key.plaintext(), null);
        store.replace(ApiKeyTestSupport.revoked(record, NOW));
        assertThat(cached.verify(key.plaintext(), null)).isInstanceOf(Valid.class); // still cached
        clock.advance(Duration.ofSeconds(31));
        assertThat(reason(cached.verify(key.plaintext(), null))).isEqualTo(Reason.REVOKED);
    }

    @Test
    void cidrBlocksNeverResolveHostNames() {
        assertThat(CidrBlock.literal("example.com")).isNull();
        assertThat(CidrBlock.literal("300.1.1.1")).isNull();
        assertThatThrownBy(() -> CidrBlock.parse("10.0.0.0/33")).isInstanceOf(IllegalArgumentException.class);
        assertThat(CidrBlock.parse("192.168.1.7").contains(CidrBlock.literal("192.168.1.7"))).isTrue();
        assertThat(CidrBlock.parse("0.0.0.0/0").contains(CidrBlock.literal("2001:db8::1"))).isFalse();
    }
}
