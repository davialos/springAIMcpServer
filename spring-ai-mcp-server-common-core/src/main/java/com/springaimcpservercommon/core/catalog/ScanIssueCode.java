package com.springaimcpservercommon.core.catalog;

/**
 * Stable codes of scan and policy-resolution issues (LLD-02 §4 and §7, LLD-03 §4, LLD-14 §3). Codes are part of
 * the public contract: they appear in logs, the admin catalog view and golden-file diffs.
 */
public enum ScanIssueCode {

    // ---- action lint (LLD-02 §4, LLD-14 §3) ----

    /** {@code readOnly=true} action whose effective {@code @Transactional} is read-write; kept, runtime guard enforces. */
    READ_ONLY_ACTION_IN_WRITE_TX(ScanIssue.Severity.WARNING),
    /** List-returning action without {@code Pageable}/{@code Limit}/paged return/limit parameter. */
    UNBOUNDED_LIST_ACTION(ScanIssue.Severity.WARNING),
    /** Tool arguments nested deeper than 2, maps, or polymorphic types. */
    COMPLEX_TOOL_ARGS(ScanIssue.Severity.WARNING),
    /** More than the threshold (default 8) of actions on one entity: consider an outcome-oriented action. */
    CONSIDER_OUTCOME_ACTION(ScanIssue.Severity.INFO),
    /** Two actions resolve to the same tool name; both are excluded. */
    DUPLICATE_TOOL_NAME(ScanIssue.Severity.ERROR),
    /** Explicit or default tool name violates {@code ^[a-z][a-z0-9_]{2,63}$}; action excluded. */
    INVALID_TOOL_NAME(ScanIssue.Severity.ERROR),
    /** Parameter names compiled away (no {@code -parameters}) and no {@code @AiParam(name)}; action excluded. */
    PARAMETER_NAMES_UNAVAILABLE(ScanIssue.Severity.ERROR),
    /** An annotated method cannot be invoked through a Spring bean proxy; ignored (ADR-0008). */
    NOT_A_SPRING_BEAN(ScanIssue.Severity.WARNING),
    /** {@code @AiExposedAction} on a web controller; controllers contribute context only. */
    CONTROLLER_ACTION_IGNORED(ScanIssue.Severity.WARNING),
    /** Several beans share one user type, so operation references would be ambiguous; their actions are excluded. */
    AMBIGUOUS_BEAN_TYPE(ScanIssue.Severity.ERROR),

    // ---- text lint ----

    /** Description, intent or meaning is blank; element excluded. */
    MISSING_DESCRIPTION(ScanIssue.Severity.ERROR),
    /** Description/intent &gt; 1024 or meaning &gt; 256 chars; element excluded. */
    DESCRIPTION_TOO_LONG(ScanIssue.Severity.ERROR),
    /** A secret-looking value in prompt-bound text; element excluded. */
    SECRET_IN_DESCRIPTION(ScanIssue.Severity.ERROR),

    // ---- attributes / members ----

    /** A member name looks sensitive but is neither {@code sensitive=true} nor confirmed; member excluded. */
    SENSITIVE_NAME_UNCONFIRMED(ScanIssue.Severity.WARNING),
    /** {@code Classification.INHERIT} used on a type; treated as INTERNAL. */
    INVALID_CLASSIFICATION(ScanIssue.Severity.WARNING),
    /** Two sources produced the same element reference; the first one is kept. */
    DUPLICATE_ELEMENT(ScanIssue.Severity.ERROR),
    /** Scanning one bean, method or entity failed unexpectedly; element skipped (fail the feature, not the host). */
    SCAN_FAILED(ScanIssue.Severity.ERROR),

    // ---- policy resolution (LLD-03 §4) ----

    /** A policy key references an element that is not in the scan (warning; error when strict). */
    POLICY_REF_UNKNOWN(ScanIssue.Severity.WARNING),
    /** A {@code Class.method} shorthand matches several overloads; the layer is treated as invalid (fail closed). */
    POLICY_SHORTHAND_AMBIGUOUS(ScanIssue.Severity.ERROR),
    /** A policy layer could not be loaded or validated; all operations and entities disabled (LLD-03 §4.3). */
    POLICY_LAYER_INVALID(ScanIssue.Severity.ERROR),
    /** A layer tried to turn a read action into a write ({@code readOnly: false}); value ignored. */
    POLICY_READ_ONLY_LOOSENING(ScanIssue.Severity.ERROR),
    /** A layer tried to lower classification/sensitivity without an OVERLAY {@code declassify} flag; value ignored. */
    POLICY_DECLASSIFY_REJECTED(ScanIssue.Severity.ERROR),
    /** A policy field does not apply to the referenced element kind; value ignored. */
    POLICY_FIELD_NOT_APPLICABLE(ScanIssue.Severity.WARNING);

    private final ScanIssue.Severity defaultSeverity;

    ScanIssueCode(ScanIssue.Severity defaultSeverity) {
        this.defaultSeverity = defaultSeverity;
    }

    /**
     * The severity used unless the scan runs in strict mode.
     *
     * @return default severity
     */
    public ScanIssue.Severity defaultSeverity() {
        return defaultSeverity;
    }
}
