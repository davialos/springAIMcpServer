package com.springaimcpservercommon.security.apikey;

import com.springaimcpservercommon.security.apikey.ApiKeyVerification.Invalid;
import com.springaimcpservercommon.security.apikey.ApiKeyVerification.Reason;
import com.springaimcpservercommon.security.internal.TtlCache;
import com.springaimcpservercommon.security.port.ApiKeyLookup;
import com.springaimcpservercommon.security.port.ApiKeyLookup.ApiKeyRecord;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Generates and verifies service-account API keys (SEC-01 §9, F-65).
 *
 * <ul>
 *   <li>Format {@code dai_<env>_<keyId>_<secret>} ({@link ApiKeyFormat}); keyId = 12 base62 chars, secret = 32 bytes
 *       from {@link SecureRandom}.</li>
 *   <li>Stored hash = {@code v<pepperVersion>:} + base64url(HMAC-SHA256(pepper, full key text)),
 *       {@code hash_algorithm = 'hmac-sha256'}. A slow password hash (argon2id, as SEC-01 §9 first sketched) adds
 *       latency without benefit for 256-bit random secrets; the server-side pepper protects a leaked table.</li>
 *   <li>Constant-time comparison ({@link MessageDigest#isEqual}); an unknown prefix still costs one HMAC so timing
 *       does not reveal which prefixes exist.</li>
 *   <li>Expiry is mandatory and at most one year; revocation, service-account status and the optional CIDR allow-list
 *       are checked on every call. Key records are cached by prefix for at most {@link #MAX_RECORD_CACHE_TTL}
 *       (30 s), bounding the revocation delay.</li>
 *   <li>{@code last_used_at} is touched at most once per key per touch interval.</li>
 * </ul>
 */
public final class ApiKeyService {

    /** Value of {@code dai_api_key.hash_algorithm} written and verified by this service. */
    public static final String HASH_ALGORITHM = "hmac-sha256";
    /** Longest allowed key lifetime. */
    public static final Duration MAX_LIFETIME = Duration.ofDays(365);
    /** Upper bound of the key-record cache TTL (revocation takes effect within it). */
    public static final Duration MAX_RECORD_CACHE_TTL = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
    private static final String HMAC = "HmacSHA256";
    private static final char[] BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final int KEY_ID_LENGTH = 12;
    private static final int SECRET_BYTES = 32;

    private final ApiKeyLookup lookup;
    private final ApiKeyPepperProvider peppers;
    private final String environment;
    private final Clock clock;
    private final SecureRandom random;
    private final Duration touchInterval;
    private final @Nullable TtlCache<String, ApiKeyRecord> records;
    private final TtlCache<UUID, Instant> touches;

    /**
     * Creates the service.
     *
     * @param lookup         key store port
     * @param peppers        pepper SPI
     * @param environment    environment label of this deployment ({@code [a-z]{2,16}}, e.g. {@code prod}); keys of
     *                       other environments are rejected
     * @param clock          time source
     * @param recordCacheTtl key-record cache TTL, zero to disable, at most 30 s
     * @param touchInterval  minimum interval between {@code last_used_at} updates per key
     */
    public ApiKeyService(ApiKeyLookup lookup, ApiKeyPepperProvider peppers, String environment, Clock clock,
                         Duration recordCacheTtl, Duration touchInterval) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.peppers = Objects.requireNonNull(peppers, "peppers");
        this.environment = ApiKeyFormat.requireEnv(environment);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = new SecureRandom();
        if (recordCacheTtl.isNegative() || recordCacheTtl.compareTo(MAX_RECORD_CACHE_TTL) > 0) {
            throw new IllegalArgumentException("recordCacheTtl must be in [0, 30s]");
        }
        if (touchInterval.isNegative() || touchInterval.isZero()) {
            throw new IllegalArgumentException("touchInterval must be positive");
        }
        this.touchInterval = touchInterval;
        this.records = recordCacheTtl.isZero() ? null : new TtlCache<>(10_000, recordCacheTtl, clock);
        this.touches = new TtlCache<>(10_000, touchInterval, clock);
    }

    /**
     * Generates a new key for this environment. The caller persists prefix, hash, algorithm and expiry and shows the
     * plaintext once.
     *
     * @param expiresAt expiry, in the future and at most one year away
     * @return the generated key
     */
    public GeneratedApiKey generate(Instant expiresAt) {
        Instant now = clock.instant();
        if (!expiresAt.isAfter(now) || expiresAt.isAfter(now.plus(MAX_LIFETIME))) {
            throw new IllegalArgumentException("API key expiry must be in the future and at most 365 days away");
        }
        char[] keyId = new char[KEY_ID_LENGTH];
        for (int i = 0; i < keyId.length; i++) {
            keyId[i] = BASE62[random.nextInt(BASE62.length)];
        }
        byte[] secretBytes = new byte[SECRET_BYTES];
        random.nextBytes(secretBytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        Arrays.fill(secretBytes, (byte) 0);
        ApiKeyFormat.ParsedApiKey key = new ApiKeyFormat.ParsedApiKey(environment, new String(keyId), secret);
        int version = peppers.currentVersion();
        String hash = "v" + version + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(version, key.text()));
        return new GeneratedApiKey(key.text(), key.prefix(), hash, HASH_ALGORITHM, expiresAt);
    }

    /**
     * Verifies a presented key.
     *
     * @param presented     key text as presented
     * @param clientAddress client IP (for the allow-list), or {@code null} if unknown
     * @return the verification result
     */
    public ApiKeyVerification verify(String presented, @Nullable InetAddress clientAddress) {
        ApiKeyFormat.ParsedApiKey key = ApiKeyFormat.parse(presented);
        if (key == null) {
            return new Invalid(Reason.MALFORMED);
        }
        if (!environment.equals(key.env())) {
            return new Invalid(Reason.WRONG_ENVIRONMENT);
        }
        Optional<ApiKeyRecord> found = findRecord(key.prefix());
        if (found.isEmpty()) {
            burnHmac(key.text());
            return new Invalid(Reason.UNKNOWN);
        }
        ApiKeyRecord record = found.get();
        Reason hashFailure = checkHash(record, key.text());
        if (hashFailure != null) {
            return new Invalid(hashFailure);
        }
        Instant now = clock.instant();
        if (record.revokedAt() != null && !record.revokedAt().isAfter(now)) {
            return new Invalid(Reason.REVOKED);
        }
        if (!record.expiresAt().isAfter(now)) {
            return new Invalid(Reason.EXPIRED);
        }
        if (!record.serviceAccountActive()) {
            return new Invalid(Reason.DISABLED);
        }
        if (!networkAllowed(record, clientAddress)) {
            return new Invalid(Reason.NETWORK_NOT_ALLOWED);
        }
        touch(record, now);
        return new ApiKeyVerification.Valid(record);
    }

    /**
     * Drops a cached key record, e.g. right after revocation on this node.
     *
     * @param keyPrefix public prefix
     */
    public void evict(String keyPrefix) {
        if (records != null) {
            records.invalidate(keyPrefix);
        }
    }

    private Optional<ApiKeyRecord> findRecord(String prefix) {
        if (records == null) {
            return lookup.findByPrefix(prefix);
        }
        Optional<ApiKeyRecord> cached = records.get(prefix);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<ApiKeyRecord> loaded = lookup.findByPrefix(prefix);
        loaded.ifPresent(r -> records.put(prefix, r));
        return loaded;
    }

    private @Nullable Reason checkHash(ApiKeyRecord record, String keyText) {
        if (!HASH_ALGORITHM.equals(record.hashAlgorithm())) {
            burnHmac(keyText);
            return Reason.UNSUPPORTED_HASH;
        }
        String stored = record.keyHash();
        int colon = stored.indexOf(':');
        if (!stored.startsWith("v") || colon < 2) {
            burnHmac(keyText);
            return Reason.UNSUPPORTED_HASH;
        }
        int version;
        byte[] expected;
        try {
            version = Integer.parseInt(stored.substring(1, colon));
            expected = Base64.getUrlDecoder().decode(stored.substring(colon + 1));
        } catch (IllegalArgumentException e) {
            burnHmac(keyText);
            return Reason.UNSUPPORTED_HASH;
        }
        byte[] actual;
        try {
            actual = hmac(version, keyText);
        } catch (IllegalArgumentException e) {
            log.warn("API key {} uses pepper version {} which is not available", record.id(), version);
            return Reason.UNSUPPORTED_HASH;
        }
        return MessageDigest.isEqual(expected, actual) ? null : Reason.MISMATCH;
    }

    private static boolean networkAllowed(ApiKeyRecord record, @Nullable InetAddress clientAddress) {
        if (record.allowedNetworks().isEmpty()) {
            return true;
        }
        if (clientAddress == null) {
            return false;
        }
        for (String network : record.allowedNetworks()) {
            try {
                if (CidrBlock.parse(network).contains(clientAddress)) {
                    return true;
                }
            } catch (IllegalArgumentException e) {
                log.warn("API key {} has an invalid allowed network entry; ignored", record.id());
            }
        }
        return false;
    }

    private void touch(ApiKeyRecord record, Instant now) {
        if (touches.get(record.id()).isPresent()) {
            return;
        }
        if (record.lastUsedAt() != null && record.lastUsedAt().plus(touchInterval).isAfter(now)) {
            touches.put(record.id(), record.lastUsedAt(), record.lastUsedAt().plus(touchInterval));
            return;
        }
        touches.put(record.id(), now);
        try {
            lookup.touchLastUsed(record.id(), now);
        } catch (RuntimeException e) {
            log.warn("could not record last use of API key {} ({})", record.id(), e.getClass().getSimpleName());
        }
    }

    private void burnHmac(String keyText) {
        try {
            hmac(peppers.currentVersion(), keyText);
        } catch (RuntimeException ignored) {
            // timing equalisation only
        }
    }

    private byte[] hmac(int version, String keyText) {
        byte[] pepper = peppers.pepper(version);
        try {
            if (pepper.length < 32) {
                throw new IllegalStateException("API key pepper must have at least 32 bytes");
            }
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(pepper, HMAC));
            return mac.doFinal(keyText.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        } finally {
            Arrays.fill(pepper, (byte) 0);
        }
    }
}
