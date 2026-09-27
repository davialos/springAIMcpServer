package com.springaimcpservercommon.persistence.identity;

/** Status of a principal reference (matches {@code ck_principal_status}). */
public enum PrincipalStatus {
    /** The subject may be mapped to a {@code DaiPrincipal}. */
    ACTIVE,
    /** The subject is blocked; the row is kept so that audit references stay resolvable. */
    DISABLED
}
