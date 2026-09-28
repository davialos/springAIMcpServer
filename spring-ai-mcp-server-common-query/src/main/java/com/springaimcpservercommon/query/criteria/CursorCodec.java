package com.springaimcpservercommon.query.criteria;

import com.springaimcpservercommon.core.json.CanonicalJson;
import com.springaimcpservercommon.query.ast.QueryDefinition;
import org.jspecify.annotations.Nullable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Encodes and verifies opaque HMAC-SHA256-signed cursor tokens for keyset pagination
 * and signed offset fallback (LLD-05 §5a, LLD-14 §3.3).
 *
 * <h3>Keyset cursor format</h3>
 * {@code base64url(payloadJson).base64url(hmacSha256)}, where {@code payloadJson} is
 * canonical JSON produced by {@link CanonicalJson}: <pre>
 * {"k":[["type","val"],...],"q":"&lt;queryId&gt;","r":&lt;revision&gt;}</pre>
 *
 * <h3>Offset fallback cursor format</h3>
 * {@code ofs:&lt;n&gt;.base64url(hmacSha256)} — used when keyset values are unavailable
 * (e.g. a sort key projected as null). The client passes it back verbatim; the executor
 * calls {@code setFirstResult(n)}.
 *
 * <h3>Type tags in {@code k}</h3>
 * <table>
 *   <tr><th>Tag</th><th>Java type</th></tr>
 *   <tr><td>{@code nil}</td><td>null</td></tr>
 *   <tr><td>{@code s}</td><td>String / Enum (by name)</td></tr>
 *   <tr><td>{@code u}</td><td>UUID</td></tr>
 *   <tr><td>{@code n}</td><td>integral number (Byte/Short/Integer/Long/BigInteger)</td></tr>
 *   <tr><td>{@code f}</td><td>floating-point (Float/Double)</td></tr>
 *   <tr><td>{@code d}</td><td>BigDecimal</td></tr>
 *   <tr><td>{@code b}</td><td>Boolean</td></tr>
 *   <tr><td>{@code t}</td><td>Instant (ISO-8601)</td></tr>
 *   <tr><td>{@code ld}</td><td>LocalDate (ISO-8601)</td></tr>
 *   <tr><td>{@code ldt}</td><td>LocalDateTime (ISO-8601)</td></tr>
 * </table>
 *
 * <p>A per-instance random 256-bit key is used by default; supply a shared key via
 * {@link #CursorCodec(byte[])} for multi-replica deployments.
 */
final class CursorCodec {

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final Base64.Encoder B64_ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DEC = Base64.getUrlDecoder();
    private static final String OFS_PREFIX = "ofs:";

    private final byte[] signingKey;

    /** Creates a codec with a random per-instance 256-bit key. */
    CursorCodec() {
        signingKey = new byte[32];
        new SecureRandom().nextBytes(signingKey);
    }

    /**
     * Creates a codec with a supplied shared key (must be at least 32 bytes).
     * Use this when running multiple replicas behind a load balancer so that cursors
     * issued by one node can be verified by another.
     */
    CursorCodec(byte[] signingKey) {
        if (signingKey.length < 32) {
            throw new IllegalArgumentException("signingKey must be at least 32 bytes");
        }
        this.signingKey = signingKey.clone();
    }

    // ── keyset cursor ─────────────────────────────────────────────────────────

    /**
     * Encodes the sort key values of the last row on the current page into an opaque keyset cursor.
     *
     * @param query      the executed query (provides binding scope)
     * @param sortValues typed sort key values from the last row, in {@link QueryDefinition#orderBy()} order
     * @return opaque cursor string
     * @throws IllegalArgumentException if any sort value has an unsupported type
     */
    String encode(QueryDefinition query, List<@Nullable Object> sortValues) {
        List<Object> kArray = new ArrayList<>(sortValues.size());
        for (@Nullable Object v : sortValues) {
            kArray.add(encodeValue(v));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("k", kArray);
        payload.put("q", query.id().toString());
        payload.put("r", (long) query.revision());
        String json = CanonicalJson.write(payload);
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        byte[] hmac = hmac(jsonBytes);
        return B64_ENC.encodeToString(jsonBytes) + "." + B64_ENC.encodeToString(hmac);
    }

    /**
     * Decodes and verifies a keyset cursor token.
     *
     * @param query  the query this cursor must be bound to (id and revision are validated)
     * @param cursor the opaque cursor string
     * @return ordered sort key values, or {@code null} if the cursor is invalid, tampered,
     *         or bound to a different query version
     */
    @Nullable List<@Nullable Object> decode(QueryDefinition query, String cursor) {
        if (cursor.startsWith(OFS_PREFIX)) return null; // not a keyset cursor
        int dot = cursor.lastIndexOf('.');
        if (dot < 1 || dot == cursor.length() - 1) return null;
        byte[] payloadBytes;
        byte[] hmacBytes;
        try {
            payloadBytes = B64_DEC.decode(cursor.substring(0, dot));
            hmacBytes = B64_DEC.decode(cursor.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!constantTimeEqual(hmac(payloadBytes), hmacBytes)) return null;
        String json = new String(payloadBytes, StandardCharsets.UTF_8);
        try {
            return new CursorPayloadParser(json).parse(query);
        } catch (Exception e) {
            return null;
        }
    }

    // ── offset fallback cursor ─────────────────────────────────────────────────

    /**
     * Encodes a signed offset cursor for the fallback offset-pagination path.
     *
     * @param offset absolute row offset for the next page
     * @return signed offset cursor string
     */
    String encodeOffset(int offset) {
        String numStr = Integer.toString(offset);
        byte[] hmac = hmac(numStr.getBytes(StandardCharsets.UTF_8));
        return OFS_PREFIX + numStr + "." + B64_ENC.encodeToString(hmac);
    }

    /**
     * Decodes and verifies a signed offset cursor.
     *
     * @param cursor the cursor string
     * @return the absolute row offset, or {@code -1} if the cursor is invalid or tampered
     */
    int decodeOffset(String cursor) {
        if (!cursor.startsWith(OFS_PREFIX)) return -1;
        int dot = cursor.lastIndexOf('.');
        if (dot <= OFS_PREFIX.length()) return -1;
        String numPart = cursor.substring(OFS_PREFIX.length(), dot);
        byte[] hmacBytes;
        try {
            hmacBytes = B64_DEC.decode(cursor.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return -1;
        }
        if (!constantTimeEqual(hmac(numPart.getBytes(StandardCharsets.UTF_8)), hmacBytes)) return -1;
        try {
            int offset = Integer.parseInt(numPart);
            return offset >= 0 ? offset : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ── value encoding / decoding ─────────────────────────────────────────────

    private static List<String> encodeValue(@Nullable Object v) {
        if (v == null) return List.of("nil", "");
        return switch (v) {
            case String s         -> List.of("s", s);
            case UUID u           -> List.of("u", u.toString());
            case Byte n           -> List.of("n", Long.toString(n));
            case Short n          -> List.of("n", Long.toString(n));
            case Integer n        -> List.of("n", Long.toString(n));
            case Long n           -> List.of("n", Long.toString(n));
            case BigInteger n     -> List.of("n", n.toString());
            case Float f          -> List.of("f", Double.toString(f.doubleValue()));
            case Double d         -> List.of("f", Double.toString(d));
            case BigDecimal d     -> List.of("d", d.toPlainString());
            case Boolean b        -> List.of("b", b.toString());
            case Instant t        -> List.of("t", t.toString());
            case LocalDate d      -> List.of("ld", d.toString());
            case LocalDateTime dt -> List.of("ldt", dt.toString());
            case Enum<?> e        -> List.of("s", e.name());
            default -> throw new IllegalArgumentException(
                    "Unsupported cursor value type: " + v.getClass().getName());
        };
    }

    static @Nullable Object decodeValue(String tag, String value) {
        return switch (tag) {
            case "nil" -> null;
            case "s"   -> value;
            case "u"   -> UUID.fromString(value);
            case "n"   -> Long.parseLong(value);
            case "f"   -> Double.parseDouble(value);
            case "d"   -> new BigDecimal(value);
            case "b"   -> Boolean.parseBoolean(value);
            case "t"   -> Instant.parse(value);
            case "ld"  -> LocalDate.parse(value);
            case "ldt" -> LocalDateTime.parse(value);
            default    -> throw new IllegalArgumentException("Unknown cursor value tag: " + tag);
        };
    }

    // ── HMAC helpers ──────────────────────────────────────────────────────────

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(signingKey, HMAC_ALGO));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static boolean constantTimeEqual(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }

    // ── cursor payload parser ─────────────────────────────────────────────────

    /**
     * Minimal recursive-descent parser for the fixed canonical payload structure:
     * {@code {"k":[["type","val"],...],"q":"<uuid>","r":<long>}}
     *
     * <p>CanonicalJson always sorts keys alphabetically (k &lt; q &lt; r), so the parser
     * relies on that order. Any deviation returns null, which the caller treats as invalid.
     */
    private static final class CursorPayloadParser {
        private final String s;
        private int i;

        CursorPayloadParser(String s) {
            this.s = s;
        }

        @Nullable List<@Nullable Object> parse(QueryDefinition query) {
            expect('{');

            String key1 = readStr();
            if (!"k".equals(key1)) return null;
            expect(':');
            List<@Nullable Object> vals = readKArray();

            if (peek() != ',') return null;
            i++;

            String key2 = readStr();
            if (!"q".equals(key2)) return null;
            expect(':');
            String qStr = readStr();

            if (peek() != ',') return null;
            i++;

            String key3 = readStr();
            if (!"r".equals(key3)) return null;
            expect(':');
            long rev = readLong();

            expect('}');

            UUID queryId;
            try {
                queryId = UUID.fromString(qStr);
            } catch (IllegalArgumentException e) {
                return null;
            }
            if (!query.id().equals(queryId)) return null;
            if (query.revision() != (int) rev) return null;

            return vals;
        }

        private List<@Nullable Object> readKArray() {
            expect('[');
            List<@Nullable Object> result = new ArrayList<>();
            if (peek() == ']') {
                i++;
                return result;
            }
            while (true) {
                expect('[');
                String tag = readStr();
                expect(',');
                String val = readStr();
                expect(']');
                result.add(CursorCodec.decodeValue(tag, val));
                if (peek() == ',') {
                    i++;
                } else {
                    break;
                }
            }
            expect(']');
            return result;
        }

        private String readStr() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char esc = s.charAt(i++);
                    switch (esc) {
                        case '"'  -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case 'n'  -> sb.append('\n');
                        case 'r'  -> sb.append('\r');
                        case 't'  -> sb.append('\t');
                        case 'b'  -> sb.append('\b');
                        case 'f'  -> sb.append('\f');
                        case 'u'  -> {
                            sb.append((char) Integer.parseInt(s, i, i + 4, 16));
                            i += 4;
                        }
                        default -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalStateException("Unterminated string in cursor payload");
        }

        private long readLong() {
            int start = i;
            if (i < s.length() && s.charAt(i) == '-') i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            return Long.parseLong(s, start, i, 10);
        }

        private void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) {
                throw new IllegalStateException(
                        "Expected '" + c + "' at pos " + i + " in cursor payload");
            }
            i++;
        }

        private char peek() {
            return i < s.length() ? s.charAt(i) : 0;
        }
    }
}
