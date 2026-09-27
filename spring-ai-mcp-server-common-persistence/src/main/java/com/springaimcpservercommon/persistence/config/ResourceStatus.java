package com.springaimcpservercommon.persistence.config;

/** Operational status of a resource (matches {@code ck_resource_status}). */
public enum ResourceStatus {
    /** Normal. */
    ACTIVE,
    /** Temporarily not served (e.g. catalog drift, LLD-03 §6); requires a reason. */
    SUSPENDED,
    /** Permanently removed from the live set. Terminal. */
    RETIRED
}
