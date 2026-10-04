package com.springaimcpservercommon.jfranalyzer.collect;

import org.jspecify.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * Masks secrets in recorded command lines before they reach any report: reports are meant to be shared, and JVM
 * arguments routinely carry {@code -Dspring.datasource.password=...} or {@code --api.token=...}.
 */
public final class Redactor {

    /** Replacement for a secret value. */
    public static final String MASK = "****";

    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "((?:-D|--|-)?[\\w.\\-]*(?:password|passwd|pwd|secret|token|apikey|api[-_.]key|credential|"
                    + "private[-_.]key|access[-_.]key)[\\w.\\-]*=)(\"[^\"]*\"|'[^']*'|\\S+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_USER_INFO = Pattern.compile("(://[^/\\s:@]+:)[^@\\s/]+@");

    private Redactor() {
    }

    /**
     * @param commandLine JVM or application arguments, may be {@code null}
     * @return the same text with secret-looking values and URL passwords masked
     */
    public static @Nullable String commandLine(@Nullable String commandLine) {
        if (commandLine == null) {
            return null;
        }
        String masked = SECRET_ASSIGNMENT.matcher(commandLine).replaceAll("$1" + MASK);
        return URL_USER_INFO.matcher(masked).replaceAll("$1" + MASK + "@");
    }
}
