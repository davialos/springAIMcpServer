package com.springaimcpservercommon.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Tiny matcher: {@code *} matches any run of characters, {@code |} separates alternatives. */
final class Glob {

    private final List<Pattern> alternatives;
    private final String source;

    Glob(String pattern) {
        this.source = pattern;
        List<Pattern> out = new ArrayList<>();
        for (String alt : pattern.split("\\|")) {
            out.add(Pattern.compile(toRegex(alt.strip())));
        }
        this.alternatives = List.copyOf(out);
    }

    private static String toRegex(String alt) {
        StringBuilder regex = new StringBuilder();
        String[] parts = alt.split("\\*", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                regex.append(".*");
            }
            if (!parts[i].isEmpty()) {
                regex.append(Pattern.quote(parts[i]));
            }
        }
        return regex.toString();
    }

    boolean matches(String value) {
        for (Pattern p : alternatives) {
            if (p.matcher(value).matches()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return source;
    }
}
