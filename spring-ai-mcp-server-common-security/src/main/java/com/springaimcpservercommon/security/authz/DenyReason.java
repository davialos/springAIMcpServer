package com.springaimcpservercommon.security.authz;

/**
 * Why an authorization decision denied access. Codes are stable (audit, problem details); they never reveal whether
 * a resource exists beyond what the caller may know.
 */
public enum DenyReason {
    /** No usable authenticated caller. */
    NOT_AUTHENTICATED,
    /** A global, workspace, resource or tool kill switch is active (step 1). */
    KILL_SWITCH,
    /** The resource has no published revision (step 2). */
    RESOURCE_NOT_PUBLISHED,
    /** The resource is suspended or retired (step 2). */
    RESOURCE_SUSPENDED,
    /** No role bundle and no non-expired grant covers the permission (step 3). */
    NO_MATCHING_GRANT,
    /** Matching grants exist but none of their ABAC conditions hold (step 4). */
    CONDITION_NOT_MET,
    /** A matching grant has conditions that cannot be parsed (fail closed, step 4). */
    INVALID_CONDITION,
    /** The resource's classification exceeds the caller's clearance (step 5). */
    CLASSIFICATION,
    /** The API key's scopes do not include the permission. */
    SCOPE_NOT_GRANTED,
    /** The request is malformed (e.g. a workspace permission without workspace). */
    INVALID_REQUEST,
    /** The decision failed with an internal error (fail closed). */
    INTERNAL_ERROR
}
