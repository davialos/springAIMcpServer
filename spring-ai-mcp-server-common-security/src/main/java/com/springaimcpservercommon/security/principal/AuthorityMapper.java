package com.springaimcpservercommon.security.principal;

import com.springaimcpservercommon.core.principal.DaiPrincipal;
import org.springframework.security.core.Authentication;

/**
 * SPI: maps the host's Spring Security {@link Authentication} to the framework's {@link DaiPrincipal}
 * (SEC-01 §3, ADR-0005). The host may replace the default ({@link DefaultAuthorityMapper}) with its own bean.
 *
 * <p>Implementations must never put credentials into the principal and must never use an e-mail address as subject id.
 */
public interface AuthorityMapper {

    /**
     * Maps an authenticated caller.
     *
     * @param authentication an authenticated, non-anonymous authentication
     * @return the framework principal
     * @throws PrincipalMappingException if the authentication is missing, anonymous, unauthenticated or unusable
     */
    DaiPrincipal map(Authentication authentication);

    /**
     * Drops cached mapping results, so the next request of every caller sees changed memberships, role mappings or
     * principal status. Called after such a change on the node that made it; other nodes see it when their cache
     * entries expire ({@code dynamic.ai.agent.security.cache-ttl}). The default does nothing (no cache).
     */
    default void invalidateAll() {
    }
}
