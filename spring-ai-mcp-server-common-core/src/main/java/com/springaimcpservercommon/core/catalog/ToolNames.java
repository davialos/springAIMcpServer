package com.springaimcpservercommon.core.catalog;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Tool-name rules (LLD-07 §2): {@code ^[a-z][a-z0-9_]{2,63}$}; the default name is the snake_case form of the
 * Java method name. Tool names are set in code only — evaluations, audit and client configurations key on them.
 */
public final class ToolNames {

    /** Pattern every tool name must match. */
    public static final Pattern PATTERN = Pattern.compile("^[a-z][a-z0-9_]{2,63}$");

    private ToolNames() {
    }

    /**
     * Whether the name is a valid tool name.
     *
     * @param name candidate
     * @return {@code true} if it matches {@link #PATTERN}
     */
    public static boolean isValid(String name) {
        return PATTERN.matcher(name).matches();
    }

    /**
     * Converts a Java identifier to snake_case: {@code findRecentOrders} → {@code find_recent_orders},
     * {@code getURLList} → {@code get_url_list}, {@code top10Orders} → {@code top10_orders}.
     * The result is not guaranteed to be a valid tool name (e.g. too short); check with {@link #isValid}.
     *
     * @param identifier Java identifier
     * @return snake_case form
     */
    public static String snakeCase(String identifier) {
        StringBuilder out = new StringBuilder(identifier.length() + 8);
        for (int i = 0; i < identifier.length(); i++) {
            char c = identifier.charAt(i);
            if (c == '$' || c == '-' || c == ' ') {
                c = '_';
            }
            if (Character.isUpperCase(c) && i > 0) {
                char prev = identifier.charAt(i - 1);
                boolean hump = Character.isLowerCase(prev) || Character.isDigit(prev);
                boolean acronymEnd = Character.isUpperCase(prev) && i + 1 < identifier.length()
                        && Character.isLowerCase(identifier.charAt(i + 1));
                if ((hump || acronymEnd) && out.length() > 0 && out.charAt(out.length() - 1) != '_') {
                    out.append('_');
                }
            }
            out.append(Character.toLowerCase(c));
        }
        String s = out.toString().replaceAll("_{2,}", "_");
        s = s.replaceAll("^_+", "").replaceAll("_+$", "");
        return s.toLowerCase(Locale.ROOT);
    }
}
