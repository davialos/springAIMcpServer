package com.springaimcpservercommon.core.lint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pattern-based {@link PiiDetector}: e-mail addresses, phone numbers, payment card numbers (Luhn-checked), IBANs
 * (mod-97-checked), US social security numbers, optionally IPv4 addresses, plus the host's own patterns (employee
 * ids, customer numbers). Deliberately conservative where a false positive would garble ordinary text (numbers are
 * validated, not just shaped) and it is a floor, not a guarantee: names and free-form addresses are not detected;
 * use a NER-backed {@link PiiDetector} for those.
 */
public final class RegexPiiDetector implements PiiDetector {

    /** What the built-in detectors look for. */
    public enum Type {
        /** E-mail address. */
        EMAIL,
        /** Phone number (9 to 15 digits with separators or a leading +). */
        PHONE,
        /** Payment card number (13 to 19 digits, Luhn). */
        CREDIT_CARD,
        /** International bank account number (mod 97). */
        IBAN,
        /** US social security number {@code 123-45-6789}. */
        US_SSN,
        /** IPv4 address (off unless asked for). */
        IPV4
    }

    /** Types enabled when none are chosen. */
    public static final Set<Type> DEFAULT_TYPES = Set.copyOf(EnumSet.of(Type.EMAIL, Type.PHONE, Type.CREDIT_CARD,
            Type.IBAN, Type.US_SSN));

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");
    private static final Pattern CARD = Pattern.compile("(?<![\\d])(?:\\d[ -]?){12,18}\\d(?![\\d])");
    private static final Pattern IBAN = Pattern.compile("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,4})?\\b");
    private static final Pattern SSN = Pattern.compile("\\b(?!000|666|9\\d\\d)\\d{3}-(?!00)\\d{2}-(?!0000)\\d{4}\\b");
    private static final Pattern PHONE = Pattern.compile("(?<![\\w.])\\+?\\d[\\d ().-]{7,18}\\d(?![\\w])");
    private static final Pattern IPV4 = Pattern.compile("\\b(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b");

    private final Set<Type> types;
    private final Map<String, Pattern> custom;

    /**
     * Creates a detector.
     *
     * @param types  built-in types to look for
     * @param custom the host's own patterns by label (upper snake case), for example {@code EMPLOYEE_ID}
     */
    public RegexPiiDetector(Set<Type> types, Map<String, Pattern> custom) {
        this.types = types.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(types));
        Map<String, Pattern> copy = new LinkedHashMap<>();
        custom.forEach((label, pattern) -> {
            if (!label.matches("[A-Z][A-Z0-9_]{1,31}")) {
                throw new IllegalArgumentException("PII label must be UPPER_SNAKE_CASE, 2 to 32 characters: " + label);
            }
            copy.put(label, Objects.requireNonNull(pattern));
        });
        this.custom = Map.copyOf(copy);
    }

    /** @return a detector with {@link #DEFAULT_TYPES} and no custom patterns */
    public static RegexPiiDetector defaults() {
        return new RegexPiiDetector(DEFAULT_TYPES, Map.of());
    }

    @Override
    public List<Match> find(String text) {
        List<Match> found = new ArrayList<>();
        // most specific first: an accepted span blocks overlapping, more general ones
        custom.forEach((label, pattern) -> collect(pattern, text, label, found, null));
        if (types.contains(Type.CREDIT_CARD)) {
            collect(CARD, text, "CREDIT_CARD", found, m -> luhn(digits(m)));
        }
        if (types.contains(Type.IBAN)) {
            collect(IBAN, text, "IBAN", found, m -> ibanValid(m.replace(" ", "")));
        }
        if (types.contains(Type.US_SSN)) {
            collect(SSN, text, "US_SSN", found, null);
        }
        if (types.contains(Type.EMAIL)) {
            collect(EMAIL, text, "EMAIL", found, null);
        }
        if (types.contains(Type.IPV4)) {
            collect(IPV4, text, "IPV4", found, null);
        }
        if (types.contains(Type.PHONE)) {
            collect(PHONE, text, "PHONE", found, m -> {
                int n = digits(m).length();
                boolean shapedLikeSomethingElse = m.matches("\\d{1,3}(\\.\\d{1,3}){3}")   // IPv4
                        || m.matches("\\d{3}-\\d{2}-\\d{4}")                        // SSN
                        || m.matches("\\d{4}-\\d{2}-\\d{2}");                       // ISO date
                return !shapedLikeSomethingElse && n >= 9 && n <= 15
                        && (m.startsWith("+") || m.matches(".*[ ().-].*"));
            });
        }
        found.sort(Comparator.comparingInt(Match::start));
        return found;
    }

    private static void collect(Pattern pattern, String text, String label, List<Match> found,
                                java.util.function.Predicate<String> accept) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            if (matcher.end() == matcher.start()) {
                continue;
            }
            String value = matcher.group();
            if (accept != null && !accept.test(value)) {
                continue;
            }
            boolean overlaps = found.stream().anyMatch(m -> matcher.start() < m.end() && matcher.end() > m.start());
            if (!overlaps) {
                found.add(new Match(label, matcher.start(), matcher.end()));
            }
        }
    }

    private static String digits(String value) {
        return value.replaceAll("\\D", "");
    }

    static boolean luhn(String digits) {
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean second = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (second) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            second = !second;
        }
        return sum % 10 == 0;
    }

    static boolean ibanValid(String iban) {
        if (iban.length() < 15 || iban.length() > 34) {
            return false;
        }
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        int remainder = 0;
        for (char c : rearranged.toCharArray()) {
            int value = Character.isDigit(c) ? c - '0' : c - 'A' + 10;
            remainder = value > 9 ? (remainder * 100 + value) % 97 : (remainder * 10 + value) % 97;
        }
        return remainder == 1;
    }
}
