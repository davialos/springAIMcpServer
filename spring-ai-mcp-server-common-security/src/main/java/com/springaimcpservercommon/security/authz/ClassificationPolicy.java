package com.springaimcpservercommon.security.authz;

/**
 * What happens when a resource's classification exceeds the caller's clearance (SEC-01 §7 step 5).
 */
public enum ClassificationPolicy {
    /** Deny the invocation (default). */
    DENY,
    /** Permit, but require the caller to mask data above the clearance ({@code Permit#maskingRequired}). */
    MASK
}
