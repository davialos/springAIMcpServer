package com.springaimcpservercommon.security.principal;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Helpers to read claim values of the various token shapes uniformly. Never logs values.
 */
final class ClaimValues {

    /** Authority prefix Spring Security uses for scopes of bearer tokens. */
    static final String SCOPE_AUTHORITY_PREFIX = "SCOPE_";

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern DN_LIKE = Pattern.compile("^(?i)(cn|ou|uid|dc|o)\\s*=.+,.+=.+$");
    private static final int MAX_VALUES = 5_000;

    private ClaimValues() {
    }

    /**
     * Looks a claim up by exact name first, then by dotted path through nested maps.
     */
    static @Nullable Object lookup(Map<String, Object> claims, String path) {
        if (claims.containsKey(path)) {
            return claims.get(path);
        }
        if (path.indexOf('.') < 0) {
            return null;
        }
        Object current = claims;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /**
     * String values of a claim: a collection or array yields its elements, a scalar yields itself.
     */
    static Set<String> strings(@Nullable Object value) {
        Set<String> out = new LinkedHashSet<>();
        addStrings(value, out, false);
        return out;
    }

    /**
     * Scope values: like {@link #strings} but space-delimited strings are split (RFC 6749 {@code scope}).
     */
    static Set<String> scopes(@Nullable Object value) {
        Set<String> out = new LinkedHashSet<>();
        addStrings(value, out, true);
        return out;
    }

    /**
     * The single string value of a claim, or {@code null}.
     */
    static @Nullable String string(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Collection<?> c) {
            return c.size() == 1 ? string(c.iterator().next()) : null;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * An instant from {@code Instant}, {@code Date} or epoch seconds.
     */
    static @Nullable Instant instant(@Nullable Object value) {
        return switch (value) {
            case Instant i -> i;
            case Date d -> d.toInstant();
            case Number n -> Instant.ofEpochSecond(n.longValue());
            case null, default -> null;
        };
    }

    /**
     * All non-null authority strings of an authentication.
     */
    static Set<String> authorities(Authentication authentication) {
        Set<String> out = new LinkedHashSet<>();
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String value = authority.getAuthority();
            if (value != null && !value.isBlank() && out.size() < MAX_VALUES) {
                out.add(value);
            }
        }
        return out;
    }

    /**
     * Scopes encoded as {@code SCOPE_x} authorities.
     */
    static Set<String> scopesFromAuthorities(Set<String> authorities) {
        Set<String> out = new LinkedHashSet<>();
        for (String authority : authorities) {
            if (authority.startsWith(SCOPE_AUTHORITY_PREFIX) && authority.length() > SCOPE_AUTHORITY_PREFIX.length()) {
                out.add(authority.substring(SCOPE_AUTHORITY_PREFIX.length()));
            }
        }
        return out;
    }

    /**
     * Whether a value looks like an e-mail address (such values are never used as subject ids).
     */
    static boolean looksLikeEmail(String value) {
        return EMAIL.matcher(value).matches();
    }

    /**
     * Whether an authority string looks like an LDAP distinguished name.
     */
    static boolean looksLikeDn(String value) {
        return DN_LIKE.matcher(value.trim()).matches();
    }

    /**
     * Normalises a DN for case-insensitive comparison: lower case, no whitespace around {@code ,}, {@code =}, {@code +}.
     */
    static String normalizeDn(String dn) {
        return dn.trim().replaceAll("\\s*([,=+])\\s*", "$1").toLowerCase(Locale.ROOT);
    }

    /**
     * Converts an attribute value to a simple ABAC value (String, Boolean, Long, BigDecimal or a list of those).
     */
    static @Nullable Object simpleValue(@Nullable Object value) {
        return switch (value) {
            case null -> null;
            case String s -> s;
            case Boolean b -> b;
            case Integer i -> i.longValue();
            case Long l -> l;
            case Short s -> s.longValue();
            case Byte b -> b.longValue();
            case BigInteger bi -> new BigDecimal(bi);
            case BigDecimal bd -> bd;
            case Number n -> BigDecimal.valueOf(n.doubleValue());
            case Collection<?> c -> {
                List<Object> list = new ArrayList<>();
                for (Object element : c) {
                    Object simple = simpleValue(element);
                    if (simple != null && !(simple instanceof List<?>) && list.size() < MAX_VALUES) {
                        list.add(simple);
                    }
                }
                yield List.copyOf(list);
            }
            case Map<?, ?> ignored -> null;
            default -> value.toString();
        };
    }

    private static void addStrings(@Nullable Object value, Set<String> out, boolean splitWhitespace) {
        switch (value) {
            case null -> {
            }
            case Collection<?> c -> {
                for (Object element : c) {
                    if (element != null && !(element instanceof Collection<?>)) {
                        addStrings(element, out, splitWhitespace);
                    }
                }
            }
            case Object[] array -> addStrings(java.util.Arrays.asList(array), out, splitWhitespace);
            case Map<?, ?> ignored -> {
            }
            default -> {
                String s = value.toString().trim();
                if (s.isEmpty()) {
                    return;
                }
                if (splitWhitespace) {
                    for (String part : WHITESPACE.split(s)) {
                        if (!part.isEmpty() && out.size() < MAX_VALUES) {
                            out.add(part);
                        }
                    }
                } else if (out.size() < MAX_VALUES) {
                    out.add(s);
                }
            }
        }
    }
}
