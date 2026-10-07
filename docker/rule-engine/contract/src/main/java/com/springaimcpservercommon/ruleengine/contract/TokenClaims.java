package com.springaimcpservercommon.ruleengine.contract;

/**
 * Names of the claims in the access token the auth service signs and the rule-engine service verifies. One owner for
 * the names (and for the media types) keeps the two services, and the protobuf {@code Session}, from drifting apart.
 * The tenant and organization ids in the token are the only scope the rule-engine service accepts: a request body or
 * query string can never widen it.
 */
public final class TokenClaims {

    /** Token issuer (URL-shaped because Spring's {@code Jwt.getIssuer()} requires it; it is never fetched). */
    public static final String ISSUER = "http://rule-engine-auth-service";
    /** Tenant the user belongs to (UUID text). */
    public static final String TENANT_ID = "tenant_id";
    /** Tenant display name. */
    public static final String TENANT_NAME = "tenant_name";
    /** Organization the user works in (UUID text); absent for a tenant-wide user. */
    public static final String ORGANIZATION_ID = "org_id";
    /** Organization display name. */
    public static final String ORGANIZATION_NAME = "org_name";
    /** {@code USER} or {@code ADMIN}. */
    public static final String ROLE = "role";
    /** Login name. */
    public static final String USERNAME = "preferred_username";
    /** Human readable name. */
    public static final String DISPLAY_NAME = "name";
    /** Protocol Buffers media type used by the login endpoints. */
    public static final String PROTOBUF = "application/x-protobuf";

    private TokenClaims() {
    }
}
