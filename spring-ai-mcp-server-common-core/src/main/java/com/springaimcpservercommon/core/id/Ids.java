package com.springaimcpservercommon.core.id;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * Generates RFC 9562 version-7 UUIDs: 48-bit Unix epoch milliseconds followed by random bits.
 *
 * <p>Time-ordered keys keep B-tree inserts append-mostly (fewer page splits than random UUIDv4) and give a rough
 * creation order, which matters for the high-volume telemetry and audit tables (LLD-15). IDs are generated in Java
 * so that entities have their identity before persisting and no database extension is required.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Clock UTC = Clock.systemUTC();

    private Ids() {
    }

    /**
     * Returns a new UUIDv7 for the current time.
     *
     * @return a new, time-ordered UUID
     */
    public static UUID newId() {
        return newId(UTC.millis());
    }

    /**
     * Returns a new UUIDv7 for the given epoch-millisecond timestamp.
     *
     * @param epochMillis milliseconds since 1970-01-01T00:00:00Z, between 0 and 2^48-1
     * @return a new UUID whose time component is {@code epochMillis}
     */
    public static UUID newId(long epochMillis) {
        if (epochMillis < 0 || epochMillis > 0xFFFF_FFFF_FFFFL) {
            throw new IllegalArgumentException("epochMillis out of UUIDv7 range: " + epochMillis);
        }
        long randA = RANDOM.nextInt(1 << 12);          // 12 random bits
        long randB = RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL; // 62 random bits
        long msb = (epochMillis << 16) | (0x7L << 12) | randA;    // version 7
        long lsb = (0x2L << 62) | randB;                          // IETF variant (10)
        return new UUID(msb, lsb);
    }

    /**
     * Extracts the creation timestamp of a UUIDv7.
     *
     * @param id a version-7 UUID
     * @return epoch milliseconds encoded in the id
     */
    public static long epochMillis(UUID id) {
        if (id.version() != 7) {
            throw new IllegalArgumentException("not a UUIDv7: " + id);
        }
        return id.getMostSignificantBits() >>> 16;
    }
}
