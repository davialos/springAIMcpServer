package com.springaimcpservercommon.security.principal;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;

/**
 * SPI: reads identity facts from one kind of host {@link Authentication}. Hosts with exotic authentication types may
 * add their own extractor in front of the defaults ({@link IdentityExtraction}).
 */
@FunctionalInterface
public interface IdentityExtractor {

    /**
     * Extracts identity facts.
     *
     * @param authentication authenticated, non-anonymous authentication
     * @param settings       claim settings
     * @return the facts, or {@code null} if this extractor does not handle the authentication
     */
    @Nullable ExtractedIdentity extract(Authentication authentication, IdentityClaimSettings settings);
}
