package com.springaimcpservercommon.security.internal;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Minimal glob used by role mappings and grant resource patterns.
 *
 * <p>Syntax: {@code *} matches any (possibly empty) sequence, {@code ?} matches exactly one character, every other
 * character matches itself. There is no escape: a literal {@code *} or {@code ?} cannot be matched. A pattern without
 * wildcards is an exact match; {@code "sg-sales-*"} is the documented prefix form. Patterns are compiled to an
 * anchored regular expression built only from quoted literals, so a pattern can never inject regex syntax.
 */
public final class GlobPattern {

    /** Upper bound on pattern length, to keep matching cost trivially bounded. */
    public static final int MAX_LENGTH = 512;

    private final String pattern;
    private final boolean caseInsensitive;
    private final boolean literal;
    private final Pattern regex;

    private GlobPattern(String pattern, boolean caseInsensitive) {
        this.pattern = pattern;
        this.caseInsensitive = caseInsensitive;
        this.literal = pattern.indexOf('*') < 0 && pattern.indexOf('?') < 0;
        this.regex = literal ? Pattern.compile("") : toRegex(pattern, caseInsensitive);
    }

    /**
     * Compiles a glob.
     *
     * @param pattern         the glob
     * @param caseInsensitive whether matching ignores case
     * @return the compiled glob
     * @throws IllegalArgumentException if empty or longer than {@link #MAX_LENGTH}
     */
    public static GlobPattern compile(String pattern, boolean caseInsensitive) {
        Objects.requireNonNull(pattern, "pattern");
        if (pattern.isEmpty() || pattern.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("glob pattern must have 1.." + MAX_LENGTH + " characters");
        }
        return new GlobPattern(pattern, caseInsensitive);
    }

    /**
     * Whether a value matches.
     *
     * @param value candidate value
     * @return {@code true} on match
     */
    public boolean matches(String value) {
        if (literal) {
            return caseInsensitive ? pattern.equalsIgnoreCase(value) : pattern.equals(value);
        }
        return regex.matcher(value).matches();
    }

    /**
     * Whether the pattern contains wildcards.
     *
     * @return {@code false} for exact patterns
     */
    public boolean hasWildcards() {
        return !literal;
    }

    private static Pattern toRegex(String glob, boolean caseInsensitive) {
        StringBuilder sb = new StringBuilder(glob.length() + 16);
        StringBuilder literalRun = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' || c == '?') {
                if (!literalRun.isEmpty()) {
                    sb.append(Pattern.quote(literalRun.toString()));
                    literalRun.setLength(0);
                }
                sb.append(c == '*' ? ".*" : ".");
            } else {
                literalRun.append(c);
            }
        }
        if (!literalRun.isEmpty()) {
            sb.append(Pattern.quote(literalRun.toString()));
        }
        int flags = Pattern.DOTALL | (caseInsensitive ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
        return Pattern.compile(sb.toString(), flags);
    }

    @Override
    public String toString() {
        return caseInsensitive ? pattern.toLowerCase(Locale.ROOT) + " (ci)" : pattern;
    }
}
