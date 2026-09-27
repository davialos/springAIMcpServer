package com.springaimcpservercommon.core.catalog;

import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.Objects;

/**
 * A finding produced while scanning the host or resolving policy layers. Messages never contain secrets,
 * prompt text or row data — only names, references and limits.
 *
 * @param code     stable issue code
 * @param severity severity (strict mode may raise warnings to errors)
 * @param element  catalog element concerned, if it has a reference
 * @param subject  human-readable subject when there is no reference (bean name, policy key, source id)
 * @param message  safe explanation including the fix
 * @param excluded whether the element was excluded (or disabled) because of this issue
 */
public record ScanIssue(ScanIssueCode code, Severity severity, @Nullable CatalogElementRef element,
                        @Nullable String subject, String message, boolean excluded) {

    /** Deterministic ordering: element/subject, then code, then message. */
    public static final Comparator<ScanIssue> ORDER = Comparator
            .comparing((ScanIssue i) -> i.element() == null ? "" : i.element().toString())
            .thenComparing(i -> i.subject() == null ? "" : i.subject())
            .thenComparing(ScanIssue::code)
            .thenComparing(ScanIssue::message);

    /** Issue severity. */
    public enum Severity {
        /** Hint only. */
        INFO,
        /** Something to fix; element may still be usable. */
        WARNING,
        /** Element excluded or layer rejected; {@code scan.strict=true} fails startup. */
        ERROR
    }

    /** Validates components. */
    public ScanIssue {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
    }

    /**
     * Issue about a catalog element with the code's default severity.
     *
     * @param code     issue code
     * @param element  element reference
     * @param message  explanation
     * @param excluded whether the element was excluded
     * @return the issue
     */
    public static ScanIssue of(ScanIssueCode code, CatalogElementRef element, String message, boolean excluded) {
        return new ScanIssue(code, code.defaultSeverity(), element, null, message, excluded);
    }

    /**
     * Issue about a subject without a catalog reference, with the code's default severity.
     *
     * @param code     issue code
     * @param subject  subject (bean name, policy key, source id)
     * @param message  explanation
     * @param excluded whether something was excluded
     * @return the issue
     */
    public static ScanIssue ofSubject(ScanIssueCode code, String subject, String message, boolean excluded) {
        return new ScanIssue(code, code.defaultSeverity(), null, subject, message, excluded);
    }

    /**
     * Returns a copy with another severity.
     *
     * @param newSeverity severity
     * @return the copy
     */
    public ScanIssue withSeverity(Severity newSeverity) {
        return new ScanIssue(code, newSeverity, element, subject, message, excluded);
    }
}
