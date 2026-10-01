package com.springaimcpservercommon.loadtest.model;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Naming rules shared by discovery and data binding (one owner for "how names relate").
 */
public final class Names {

    /**
     * Names of secrets and personal identifiers: never sampled from a database into test data files.
     */
    private static final Pattern SENSITIVE = Pattern.compile(
            "(password|passwd|secret|token|apikey|accesskey|credential|socialsecurity|taxid|nationalid|passport"
                    + "|cardnumber|creditcard|accountnumber)");

    /** Short sensitive words, matched as whole words only ({@code pin} must not match {@code shipping}). */
    private static final java.util.Set<String> SENSITIVE_WORDS =
            java.util.Set.of("pwd", "pin", "otp", "ssn", "cvv", "cvc", "iban", "salt", "hash");

    private Names() {
    }

    /**
     * Spring Boot's default physical naming ({@code CamelCaseToUnderscoresNamingStrategy}):
     * {@code orderLine} → {@code order_line}.
     *
     * @param name Java name
     * @return snake-case name
     */
    public static String snakeCase(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("([A-Z])([A-Z][a-z])", "$1_$2")
                .toLowerCase(Locale.ROOT);
    }

    /**
     * Lower-case form without separators, for fuzzy comparison: {@code order_Line-ID} → {@code orderlineid}.
     *
     * @param name any name
     * @return normalized name
     */
    public static String normalize(String name) {
        return name.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    /**
     * Naive English singular of a normalized collection name: {@code categories} → {@code category},
     * {@code addresses} → {@code address}, {@code users} → {@code user}.
     *
     * @param name normalized name
     * @return singular form
     */
    public static String singular(String name) {
        if (name.endsWith("ies") && name.length() > 3) {
            return name.substring(0, name.length() - 3) + "y";
        }
        if (name.endsWith("sses") || name.endsWith("xes") || name.endsWith("ches") || name.endsWith("shes")) {
            return name.substring(0, name.length() - 2);
        }
        if (name.endsWith("s") && !name.endsWith("ss") && name.length() > 1) {
            return name.substring(0, name.length() - 1);
        }
        return name;
    }

    /**
     * Whether a field name denotes a secret or a personal identifier.
     *
     * @param name field name
     * @return {@code true} if sensitive
     */
    public static boolean isSensitive(String name) {
        if (SENSITIVE.matcher(normalize(name)).find()) {
            return true;
        }
        for (String word : snakeCase(name).split("[^a-z0-9]+")) {
            if (SENSITIVE_WORDS.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A valid, readable JavaScript identifier for a name: {@code get-user by id} → {@code getUserById}.
     *
     * @param name any name
     * @return identifier
     */
    public static String jsIdentifier(String name) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : name.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '_') {
                out.append(upper && out.length() > 0 ? Character.toUpperCase(c) : c);
                upper = false;
            } else {
                upper = true;
            }
        }
        if (out.isEmpty() || Character.isDigit(out.charAt(0))) {
            out.insert(0, "op");
        }
        return out.toString();
    }
}
