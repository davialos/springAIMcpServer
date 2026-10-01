package com.springaimcpservercommon.core.guard;

/** Kinds of malicious prompt content recognised by {@link MaliciousPromptValidator}. */
public enum ThreatCategory {

    /** Attempts to override the assistant's instructions or role ("ignore all previous instructions"). */
    PROMPT_INJECTION,
    /** Known jailbreak framings ("developer mode", "do anything now", "no restrictions"). */
    JAILBREAK,
    /** Attempts to read the system prompt or hidden instructions. */
    SYSTEM_PROMPT_EXTRACTION,
    /** SQL injection payloads ({@code ' OR 1=1}, {@code UNION SELECT}, {@code ; DROP TABLE}). */
    SQL_INJECTION,
    /** Script or markup injection ({@code <script>}, {@code javascript:}, event handlers). */
    SCRIPT_INJECTION,
    /** Operating-system command injection ({@code ; rm -rf}, {@code $(…)}, {@code /etc/passwd}). */
    COMMAND_INJECTION,
    /** Path traversal ({@code ../../}). */
    PATH_TRAVERSAL,
    /** Bulk extraction of credentials, regulated identifiers or other users' data, or sending data elsewhere. */
    DATA_EXFILTRATION,
    /** Hidden or encoded content: invisible Unicode, bidi overrides, encoded payloads carrying any of the above. */
    OBFUSCATION
}
