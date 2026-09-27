package com.springaimcpservercommon.core.hash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 digests rendered as {@code sha256:<64 lowercase hex chars>}, the format enforced by the store's CHECK
 * constraints (spec hashes, args/result hashes, audit chain hashes).
 */
public final class Sha256 {

    /** Prefix of every rendered digest. */
    public static final String PREFIX = "sha256:";

    private static final HexFormat HEX = HexFormat.of();

    private Sha256() {
    }

    /**
     * Hashes UTF-8 text.
     *
     * @param text the text to hash
     * @return {@code sha256:<hex>}
     */
    public static String of(String text) {
        return of(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Hashes bytes.
     *
     * @param bytes the bytes to hash
     * @return {@code sha256:<hex>}
     */
    public static String of(byte[] bytes) {
        return PREFIX + HEX.formatHex(digest().digest(bytes));
    }

    /**
     * Checks whether a string is a well-formed rendered digest.
     *
     * @param value candidate value
     * @return {@code true} if it matches {@code sha256:[0-9a-f]{64}}
     */
    public static boolean isValid(String value) {
        return value.length() == PREFIX.length() + 64
                && value.startsWith(PREFIX)
                && value.substring(PREFIX.length()).chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JRE", e);
        }
    }
}
