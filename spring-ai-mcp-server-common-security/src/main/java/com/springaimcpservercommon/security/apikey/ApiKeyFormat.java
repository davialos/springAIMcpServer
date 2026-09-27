package com.springaimcpservercommon.security.apikey;

import org.jspecify.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * API key text format {@code dai_<env>_<keyId>_<secret>} (SEC-01 §9):
 * <ul>
 *   <li>{@code env}: lower-case environment label ({@code [a-z]{2,16}}, e.g. {@code prod});</li>
 *   <li>{@code keyId}: 12 base62 characters (8–32 accepted, matching {@code ck_api_key_prefix});</li>
 *   <li>{@code secret}: 32 random bytes, base64url without padding (43 characters, may contain {@code _} and
 *       {@code -});</li>
 *   <li>public prefix = {@code dai_<env>_<keyId>} ({@code dai_api_key.key_prefix}).</li>
 * </ul>
 */
public final class ApiKeyFormat {

    /** Longest accepted key text. */
    public static final int MAX_LENGTH = 128;

    private static final Pattern KEY =
            Pattern.compile("^dai_([a-z]{2,16})_([A-Za-z0-9]{8,32})_([A-Za-z0-9_-]{43})$");
    private static final Pattern ENV = Pattern.compile("^[a-z]{2,16}$");

    private ApiKeyFormat() {
    }

    /**
     * A syntactically valid key.
     *
     * @param env    environment label
     * @param keyId  key id
     * @param secret secret part
     */
    public record ParsedApiKey(String env, String keyId, String secret) {

        /**
         * The public prefix.
         *
         * @return {@code dai_<env>_<keyId>}
         */
        public String prefix() {
            return prefix(env, keyId);
        }

        /**
         * The full key text.
         *
         * @return the key
         */
        public String text() {
            return prefix() + "_" + secret;
        }

        @Override
        public String toString() {
            return "ParsedApiKey[" + prefix() + "_***]";
        }
    }

    /**
     * Parses a presented key.
     *
     * @param text key text
     * @return the parsed key, or {@code null} if malformed
     */
    public static @Nullable ParsedApiKey parse(@Nullable String text) {
        if (text == null || text.length() > MAX_LENGTH) {
            return null;
        }
        Matcher m = KEY.matcher(text);
        if (!m.matches()) {
            return null;
        }
        return new ParsedApiKey(m.group(1), m.group(2), m.group(3));
    }

    /**
     * Builds a prefix.
     *
     * @param env   environment label
     * @param keyId key id
     * @return {@code dai_<env>_<keyId>}
     */
    public static String prefix(String env, String keyId) {
        return "dai_" + env + "_" + keyId;
    }

    /**
     * Validates an environment label.
     *
     * @param env label
     * @return the label
     * @throws IllegalArgumentException if invalid
     */
    public static String requireEnv(String env) {
        if (!ENV.matcher(env).matches()) {
            throw new IllegalArgumentException("API key environment label must match [a-z]{2,16}");
        }
        return env;
    }
}
