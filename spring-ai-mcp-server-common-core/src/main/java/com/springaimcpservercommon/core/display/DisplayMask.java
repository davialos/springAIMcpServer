package com.springaimcpservercommon.core.display;

/**
 * Masking applied to a displayed value before personal-data redaction. A template can only add masking: sensitive
 * attributes are always fully masked whatever the template says.
 */
public enum DisplayMask {
    /** Shown as is (still subject to personal-data redaction). */
    NONE,
    /** All but the last four characters replaced ({@code ••••1234}); values of eight characters or fewer are fully masked. */
    PARTIAL,
    /** Replaced entirely. */
    FULL
}
