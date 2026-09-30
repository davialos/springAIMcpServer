package com.springaimcpservercommon.core.versioning;

import java.util.Objects;

/** Result of looking up the current version of one record. */
public sealed interface VersionLookup permits VersionLookup.Found, VersionLookup.Missing, VersionLookup.Unsupported {

    /**
     * The record exists.
     *
     * @param token its current version
     */
    record Found(VersionToken token) implements VersionLookup {
        /** Validates the token. */
        public Found {
            Objects.requireNonNull(token, "token");
        }
    }

    /** The record does not exist (never did, or was deleted). */
    record Missing() implements VersionLookup {
    }

    /** This adapter cannot read the record (unknown entity, unsupported id type, no versioning). */
    record Unsupported() implements VersionLookup {
    }
}
