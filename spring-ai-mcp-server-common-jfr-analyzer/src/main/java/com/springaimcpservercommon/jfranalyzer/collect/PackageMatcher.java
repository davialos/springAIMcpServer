package com.springaimcpservercommon.jfranalyzer.collect;

import java.util.List;

/**
 * Decides whether a class belongs to the requested packages. A prefix matches the package itself and every
 * sub-package ({@code com.acme} matches {@code com.acme.Foo} and {@code com.acme.order.Bar}, not
 * {@code com.acmexyz.Baz}); a fully qualified class name also works and covers its nested classes. A trailing {@code .*} or {@code .**} is accepted and ignored. Exclusions win.
 */
public final class PackageMatcher {

    private final List<String> includes;
    private final List<String> excludes;

    /**
     * @param includes package prefixes; empty matches every class
     * @param excludes package prefixes that never match
     */
    public PackageMatcher(List<String> includes, List<String> excludes) {
        this.includes = includes.stream().map(PackageMatcher::normalize).filter(s -> !s.isEmpty()).toList();
        this.excludes = excludes.stream().map(PackageMatcher::normalize).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * @param className fully qualified (binary) class name
     * @return whether the class is in a requested package and not excluded
     */
    public boolean matches(String className) {
        for (String prefix : excludes) {
            if (under(className, prefix)) {
                return false;
            }
        }
        if (includes.isEmpty()) {
            return true;
        }
        for (String prefix : includes) {
            if (under(className, prefix)) {
                return true;
            }
        }
        return false;
    }

    /** @return whether any include prefix was given */
    public boolean restricted() {
        return !includes.isEmpty();
    }

    private static boolean under(String className, String prefix) {
        return className.startsWith(prefix)
                && (className.length() == prefix.length() || className.charAt(prefix.length()) == '.'
                || className.charAt(prefix.length()) == '$');
    }

    static String normalize(String raw) {
        String s = raw.strip().replace('/', '.');
        while (s.endsWith("*") || s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
