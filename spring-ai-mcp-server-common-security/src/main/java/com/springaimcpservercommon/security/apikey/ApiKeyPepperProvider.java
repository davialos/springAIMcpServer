package com.springaimcpservercommon.security.apikey;

/**
 * SPI: supplies the server-side pepper (HMAC key) for API key hashing from the host's secret store (SEC-01 §9,
 * {@code dai_api_key.hash_algorithm = 'hmac-sha256'}). The pepper never lives in the database.
 *
 * <p><b>Rotation:</b> every stored hash records the pepper version it was made with ({@code v<version>:…}). New keys
 * use {@link #currentVersion()}; verification asks for the version recorded in the key's hash. To rotate, add a new
 * version, make it current, and keep the old version resolvable until all keys hashed with it have expired or been
 * re-issued.
 */
public interface ApiKeyPepperProvider {

    /**
     * Version used for new keys.
     *
     * @return current version, ≥ 1
     */
    int currentVersion();

    /**
     * Returns the pepper of a version. Implementations must return a copy (callers may wipe it).
     *
     * @param version pepper version
     * @return at least 32 bytes of secret key material
     * @throws IllegalArgumentException if the version is unknown or retired
     */
    byte[] pepper(int version);
}
