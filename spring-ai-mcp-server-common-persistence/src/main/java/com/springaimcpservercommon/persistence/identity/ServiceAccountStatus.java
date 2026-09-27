package com.springaimcpservercommon.persistence.identity;

/** Status of a service account (matches {@code ck_service_account_status}). */
public enum ServiceAccountStatus {
    /** Keys of the account authenticate. */
    ACTIVE,
    /** No key of the account authenticates. */
    DISABLED
}
