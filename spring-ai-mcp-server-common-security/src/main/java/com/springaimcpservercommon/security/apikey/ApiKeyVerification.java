package com.springaimcpservercommon.security.apikey;

import com.springaimcpservercommon.security.port.ApiKeyLookup.ApiKeyRecord;

import java.util.Objects;

/**
 * Result of {@link ApiKeyService#verify}. The reason of an invalid result is for audit and metrics only: callers
 * must answer every failure with the same generic 401 so the reason never becomes an oracle.
 */
public sealed interface ApiKeyVerification permits ApiKeyVerification.Valid, ApiKeyVerification.Invalid {

    /**
     * The key is valid.
     *
     * @param key the key record
     */
    record Valid(ApiKeyRecord key) implements ApiKeyVerification {
        /**
         * Validates components.
         */
        public Valid {
            Objects.requireNonNull(key, "key");
        }
    }

    /**
     * The key was rejected.
     *
     * @param reason why
     */
    record Invalid(Reason reason) implements ApiKeyVerification {
        /**
         * Validates components.
         */
        public Invalid {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Rejection reasons. */
    enum Reason {
        /** Not of the form {@code dai_<env>_<keyId>_<secret>}. */
        MALFORMED,
        /** Issued for another environment label. */
        WRONG_ENVIRONMENT,
        /** No key with that prefix. */
        UNKNOWN,
        /** Secret does not match the stored hash. */
        MISMATCH,
        /** Past its expiry. */
        EXPIRED,
        /** Revoked. */
        REVOKED,
        /** Service account disabled. */
        DISABLED,
        /** Client address not in the key's allow-list. */
        NETWORK_NOT_ALLOWED,
        /** Stored hash algorithm or pepper version not supported by this node. */
        UNSUPPORTED_HASH
    }
}
