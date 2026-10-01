package com.springaimcpservercommon.core.guard;

import com.springaimcpservercommon.core.lint.SecretScanner;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Built-in {@link PiiDetector}: well-known formats found by regular expression and, where the format has one,
 * confirmed by its checksum so that order numbers and amounts are not mistaken for personal data (F-76).
 *
 * <ul>
 *   <li>{@link PiiType#EMAIL} — {@code local@domain.tld}</li>
 *   <li>{@link PiiType#PHONE} — {@code +<country> …} with 8–15 digits, or North American {@code (555) 123-4567}
 *       / {@code 555-123-4567}; bare digit runs are not treated as phone numbers</li>
 *   <li>{@link PiiType#CREDIT_CARD} — 13–19 digits, optionally grouped by spaces or dashes, Luhn-valid</li>
 *   <li>{@link PiiType#IBAN} — ISO 13616, mod-97 valid, compact or grouped by spaces</li>
 *   <li>{@link PiiType#NATIONAL_ID} — US social security number {@code 123-45-6789} (dashes required)</li>
 *   <li>{@link PiiType#IP_ADDRESS} — IPv4 and IPv6</li>
 *   <li>{@link PiiType#CREDENTIAL} — everything {@link SecretScanner} recognises (tokens, keys, passwords)</li>
 * </ul>
 *
 * <p>Stateless and thread-safe.
 */
public final class RegexPiiDetector implements PiiDetector {

    private static final Pattern EMAIL = Pattern.compile(
            "(?<![\\w.+-])[A-Za-z0-9](?:[A-Za-z0-9._%+-]{0,62}[A-Za-z0-9_%+-])?"
                    + "@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*\\.[A-Za-z]{2,24}(?![\\w-])");
    private static final Pattern PHONE_INTERNATIONAL = Pattern.compile(
            "(?<![\\w+])\\+\\d{1,3}(?:[ .-]?\\(?\\d{1,5}\\)?){1,6}(?!\\w)");
    private static final Pattern PHONE_NORTH_AMERICA = Pattern.compile(
            "(?<![\\w(])(?:\\(\\d{3}\\) ?|\\d{3}[ .-])\\d{3}[ .-]\\d{4}(?!\\w)");
    private static final Pattern CARD = Pattern.compile("(?<![\\w-])(?:\\d[ -]?){12,18}\\d(?![\\w-])");
    private static final Pattern IBAN = Pattern.compile("(?<![A-Za-z0-9])[A-Z]{2}\\d{2}(?: ?[A-Z0-9]){11,32}");
    private static final Pattern US_SSN = Pattern.compile(
            "(?<![\\w-])(?!000|666|9\\d\\d)\\d{3}-(?!00)\\d{2}-(?!0000)\\d{4}(?![\\w-])");
    private static final Pattern IPV4 = Pattern.compile(
            "(?<![\\w.])(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)"
                    + "(?!\\w)(?!\\.\\d)");
    private static final Pattern IPV6 = Pattern.compile(
            "(?<![\\w:])(?:(?:[0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4}"
                    + "|(?:[0-9A-Fa-f]{1,4}:){1,6}:(?:[0-9A-Fa-f]{1,4}:){0,5}[0-9A-Fa-f]{1,4})(?![\\w:])");

    private static final int IBAN_MIN = 15;
    private static final int IBAN_MAX = 34;

    private final SecretScanner secrets;

    /** Creates the detector with the default credential patterns. */
    public RegexPiiDetector() {
        this(new SecretScanner());
    }

    /**
     * Creates the detector with a specific credential scanner (e.g. one with host token formats).
     *
     * @param secrets credential scanner
     */
    public RegexPiiDetector(SecretScanner secrets) {
        this.secrets = Objects.requireNonNull(secrets, "secrets");
    }

    @Override
    public List<PiiMatch> detect(String text) {
        List<PiiMatch> out = new ArrayList<>();
        simple(EMAIL, PiiType.EMAIL, text, out);
        simple(US_SSN, PiiType.NATIONAL_ID, text, out);
        simple(PHONE_NORTH_AMERICA, PiiType.PHONE, text, out);
        simple(IPV4, PiiType.IP_ADDRESS, text, out);
        simple(IPV6, PiiType.IP_ADDRESS, text, out);
        Matcher m = PHONE_INTERNATIONAL.matcher(text);
        while (m.find()) {
            int digits = countDigits(text, m.start(), m.end());
            if (digits >= 8 && digits <= 15) {
                out.add(new PiiMatch(PiiType.PHONE, m.start(), m.end()));
            }
        }
        m = CARD.matcher(text);
        while (m.find()) {
            String digits = digitsOf(text, m.start(), m.end());
            if (digits.length() >= 13 && digits.length() <= 19 && luhnValid(digits)) {
                out.add(new PiiMatch(PiiType.CREDIT_CARD, m.start(), m.end()));
            }
        }
        m = IBAN.matcher(text);
        while (m.find()) {
            int end = ibanEnd(text, m.start(), m.end());
            if (end > 0) {
                out.add(new PiiMatch(PiiType.IBAN, m.start(), end));
            }
        }
        for (SecretScanner.SecretSpan s : secrets.findAll(text)) {
            out.add(new PiiMatch(PiiType.CREDENTIAL, s.start(), s.end()));
        }
        return out;
    }

    private static void simple(Pattern pattern, PiiType type, String text, List<PiiMatch> out) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            out.add(new PiiMatch(type, m.start(), m.end()));
        }
    }

    private static int countDigits(String text, int start, int end) {
        int n = 0;
        for (int i = start; i < end; i++) {
            if (Character.isDigit(text.charAt(i))) {
                n++;
            }
        }
        return n;
    }

    private static String digitsOf(String text, int start, int end) {
        StringBuilder sb = new StringBuilder(end - start);
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Luhn (mod 10) check used by payment card numbers. */
    static boolean luhnValid(String digits) {
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0 && !digits.chars().allMatch(c -> c == '0');
    }

    /**
     * The regex may run on into following upper-case words ("… 0130 00 AND"), so the longest prefix of 15–34
     * alphanumerics that passes the ISO 13616 checksum wins.
     *
     * @return end index of the valid IBAN, or {@code -1}
     */
    private static int ibanEnd(String text, int start, int end) {
        List<Integer> positions = new ArrayList<>();
        StringBuilder compact = new StringBuilder();
        for (int i = start; i < end && compact.length() < IBAN_MAX; i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                compact.append(c);
                positions.add(i);
            }
        }
        for (int len = compact.length(); len >= IBAN_MIN; len--) {
            int after = positions.get(len - 1) + 1;
            if (after < text.length() && Character.isLetterOrDigit(text.charAt(after))) {
                continue; // would cut a word in half
            }
            if (ibanValid(compact.substring(0, len))) {
                return after;
            }
        }
        return -1;
    }

    /** ISO 13616 mod-97 check. */
    static boolean ibanValid(String iban) {
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        StringBuilder numeric = new StringBuilder(rearranged.length() * 2);
        for (int i = 0; i < rearranged.length(); i++) {
            char c = rearranged.charAt(i);
            if (c >= '0' && c <= '9') {
                numeric.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                numeric.append(c - 'A' + 10);
            } else {
                return false;
            }
        }
        return new BigInteger(numeric.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }
}
