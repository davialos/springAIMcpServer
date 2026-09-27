package com.springaimcpservercommon.core.lint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Sensitive-name heuristic (LLD-02 §4): an attribute or DTO member whose name looks like a credential or
 * regulated identifier ({@code password, secret, token, apiKey, credential, ssn, iban, cardNumber, cvv, pin}) but
 * is not declared {@code sensitive = true} is excluded unless the host explicitly confirms it via
 * {@code dynamic.ai.agent.scan.confirm-sensitive-names}. This guards against a copy-pasted {@code sensitive=false}.
 *
 * <p>Matching is token based, not substring based: the name is split into camelCase / snake_case tokens and a
 * term matches when it equals one token or a run of consecutive tokens ({@code apiKey} → {@code api}+{@code key}).
 * So {@code pinCode} matches {@code pin} while {@code shippingAddress} does not.
 */
public final class SensitiveNames {

    /** Default terms from LLD-02 §4. */
    public static final Set<String> DEFAULT_TERMS = Set.of(
            "password", "secret", "token", "apiKey", "credential", "ssn", "iban", "cardNumber", "cvv", "pin");

    /** Result of checking a member name. */
    public enum Verdict {
        /** The name does not look sensitive. */
        NOT_SENSITIVE,
        /** The name looks sensitive but the host explicitly confirmed it may be exposed. */
        CONFIRMED,
        /** The name looks sensitive and was not confirmed: exclude it and report an issue. */
        UNCONFIRMED
    }

    private final List<List<String>> terms;
    private final Set<String> confirmed;

    /**
     * Creates the heuristic.
     *
     * @param terms          sensitive terms (camelCase or snake_case; compared token-wise, case-insensitively)
     * @param confirmedNames names confirmed as safe to expose: either a bare member name ({@code pinned}) or a
     *                       qualified {@code fully.qualified.Type#member}; compared case-insensitively
     */
    public SensitiveNames(Set<String> terms, Set<String> confirmedNames) {
        this.terms = terms.stream().map(SensitiveNames::tokens).filter(t -> !t.isEmpty()).toList();
        this.confirmed = confirmedNames.stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Heuristic with the default terms and no confirmations.
     *
     * @return the default heuristic
     */
    public static SensitiveNames defaults() {
        return new SensitiveNames(DEFAULT_TERMS, Set.of());
    }

    /**
     * Whether a name looks sensitive regardless of confirmations.
     *
     * @param name member name
     * @return {@code true} if any term matches
     */
    public boolean looksSensitive(String name) {
        List<String> nameTokens = tokens(name);
        for (List<String> term : terms) {
            if (containsRun(nameTokens, term)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks a member of a type.
     *
     * @param ownerType fully qualified name of the declaring type
     * @param name      member name
     * @return the verdict
     */
    public Verdict check(String ownerType, String name) {
        if (!looksSensitive(name)) {
            return Verdict.NOT_SENSITIVE;
        }
        String bare = name.toLowerCase(Locale.ROOT);
        String qualified = (ownerType + "#" + name).toLowerCase(Locale.ROOT);
        return confirmed.contains(bare) || confirmed.contains(qualified) ? Verdict.CONFIRMED : Verdict.UNCONFIRMED;
    }

    private static boolean containsRun(List<String> haystack, List<String> needle) {
        outer:
        for (int i = 0; i + needle.size() <= haystack.size(); i++) {
            for (int j = 0; j < needle.size(); j++) {
                if (!haystack.get(i + j).equals(needle.get(j))) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Splits an identifier into lowercase tokens at camelCase humps, digits and non-alphanumerics.
     *
     * @param name identifier
     * @return lowercase tokens
     */
    static List<String> tokens(String name) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                flush(out, current);
                continue;
            }
            if (current.length() > 0) {
                char prev = name.charAt(i - 1);
                boolean hump = Character.isUpperCase(c) && (Character.isLowerCase(prev) || Character.isDigit(prev));
                boolean acronymEnd = Character.isUpperCase(c) && Character.isUpperCase(prev)
                        && i + 1 < name.length() && Character.isLowerCase(name.charAt(i + 1));
                boolean digitBoundary = Character.isDigit(c) != Character.isDigit(prev);
                if (hump || acronymEnd || digitBoundary) {
                    flush(out, current);
                }
            }
            current.append(Character.toLowerCase(c));
        }
        flush(out, current);
        return out;
    }

    private static void flush(List<String> out, StringBuilder current) {
        if (current.length() > 0) {
            out.add(current.toString());
            current.setLength(0);
        }
    }
}
