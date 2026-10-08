package com.springaimcpservercommon.celfaker.contract;

/** What an API is used for in a workflow. */
public enum ApiRole {
    /** Creates or changes something; receives generated request data. */
    ACTION,
    /** Checks the result of an action (a "validate" endpoint); receives no generated body. */
    VALIDATION
}
