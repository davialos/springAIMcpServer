package com.springaimcpservercommon.security.authz;

import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.security.principal.AuthorityMapper;
import com.springaimcpservercommon.security.principal.PrincipalMappingException;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Spring Security 7 {@link AuthorizationManager} over the framework engine (SEC-01 §7): maps the current
 * {@link Authentication} with the {@link AuthorityMapper} and decides with the {@link AuthorizationEngine}.
 * Unauthenticated, anonymous or unmappable callers are denied (and audited).
 */
public final class DaiAuthorizationManager implements AuthorizationManager<InvocationTarget> {

    private final AuthorityMapper mapper;
    private final AuthorizationEngine engine;

    /**
     * Creates the manager.
     *
     * @param mapper principal mapper
     * @param engine decision engine
     */
    public DaiAuthorizationManager(AuthorityMapper mapper, AuthorizationEngine engine) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends @Nullable Authentication> authentication,
                                         InvocationTarget target) {
        return new DaiAuthorizationDecision(decide(authentication.get(), target));
    }

    /**
     * Decides for an authentication.
     *
     * @param authentication current authentication, may be {@code null}
     * @param target         target
     * @return the outcome
     */
    public AuthorizationOutcome decide(@Nullable Authentication authentication, InvocationTarget target) {
        DaiPrincipal principal = mapOrNull(authentication);
        if (principal == null) {
            return engine.denyUnauthenticated(target.permission(), target.workspaceId(),
                    target.resource() == null ? null : target.resource().resourceId());
        }
        return engine.decide(target.toRequest(principal));
    }

    private @Nullable DaiPrincipal mapOrNull(@Nullable Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        try {
            return mapper.map(authentication);
        } catch (PrincipalMappingException e) {
            return null;
        }
    }
}
