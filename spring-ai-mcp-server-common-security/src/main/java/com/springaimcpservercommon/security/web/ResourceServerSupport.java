package com.springaimcpservercommon.security.web;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.util.ClassUtils;

/**
 * Enables the OAuth 2.0 resource server on one of our chains, reusing the host's {@code JwtDecoder} or
 * {@code OpaqueTokenIntrospector} bean. The only class of this module that references resource-server/jose types;
 * it is only called by {@link DynamicAiHttpSecurityConfigurer} after a {@code ClassUtils.isPresent} check, so the
 * JVM never links it on hosts without those modules.
 */
public final class ResourceServerSupport {

    private static final String JOSE_CLASS = "org.springframework.security.oauth2.jwt.JwtDecoder";

    private ResourceServerSupport() {
    }

    /**
     * Configures {@code oauth2ResourceServer} with the host's decoder or introspector.
     *
     * @param http          chain builder
     * @param host          host capabilities (decoder/introspector beans)
     * @param entryPoint    our 401 entry point (RFC 6750 / RFC 9728 challenges)
     * @param deniedHandler our 403 handler
     */
    static void apply(HttpSecurity http, HostAuthenticationCapabilities host, AuthenticationEntryPoint entryPoint,
                      AccessDeniedHandler deniedHandler) {
        Object decoder = host.jwtDecoder();
        Object introspector = host.opaqueTokenIntrospector();
        http.oauth2ResourceServer(rs -> {
            if (decoder != null && ClassUtils.isPresent(JOSE_CLASS, ResourceServerSupport.class.getClassLoader())) {
                rs.jwt(jwt -> jwt.decoder((JwtDecoder) decoder));
            } else if (introspector != null) {
                rs.opaqueToken(opaque -> opaque.introspector((OpaqueTokenIntrospector) introspector));
            }
            rs.authenticationEntryPoint(entryPoint);
            rs.accessDeniedHandler(deniedHandler);
        });
    }
}
