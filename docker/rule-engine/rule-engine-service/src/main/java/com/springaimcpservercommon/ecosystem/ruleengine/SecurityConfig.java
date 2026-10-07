package com.springaimcpservercommon.ecosystem.ruleengine;

import com.springaimcpservercommon.ruleengine.contract.TokenClaims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

/**
 * Stateless resource server. Every {@code /api/**} call needs a valid access token from the auth service; the logs are
 * ADMIN only (decided here, once, and not repeated in each controller); the token's claims are the only source of the
 * caller's tenant, organization and role. Oversized bodies are refused before they are read.
 */
@Configuration
class SecurityConfig {

    @Bean
    JwtDecoder jwtDecoder(RuleEngineProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(
                new SecretKeySpec(props.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(TokenClaims.ISSUER));
        return decoder;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtDecoder decoder, RuleEngineProperties props)
            throws Exception {
        Converter<Jwt, AbstractAuthenticationToken> converter = jwt -> {
            Caller caller;
            try {
                caller = Caller.from(jwt); // a token without tenant claims is not a token of this system
            } catch (RuntimeException e) {
                throw new org.springframework.security.oauth2.core.OAuth2AuthenticationException(
                        new org.springframework.security.oauth2.core.OAuth2Error("invalid_token",
                                "the token has no usable tenant scope", null));
            }
            Collection<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(
                    caller.admin() ? "ROLE_ADMIN" : "ROLE_USER"));
            return new JwtAuthenticationToken(jwt, authorities, caller.username());
        };
        http.csrf(csrf -> csrf.disable()) // stateless bearer API: no cookie to forge
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new BodyLimitFilter(props.maxBodyBytes()), BasicAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus", "/error")
                        .permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(converter)))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> problem(res, HttpStatus.UNAUTHORIZED,
                                "unauthenticated", "a valid access token is required"))
                        .accessDeniedHandler((req, res, ex) -> problem(res, HttpStatus.FORBIDDEN, "forbidden",
                                "your role may not use this resource")));
        return http.build();
    }

    private static void problem(HttpServletResponse res, HttpStatus status, String code, String message)
            throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        res.getWriter().write("{\"status\":" + status.value() + ",\"title\":\"" + status.getReasonPhrase()
                + "\",\"detail\":\"" + message + "\",\"code\":\"" + code + "\"}");
    }

    /** Refuses a request whose declared body is larger than the limit (cheap defence against memory abuse). */
    static final class BodyLimitFilter extends OncePerRequestFilter {
        private final long max;

        BodyLimitFilter(long max) {
            this.max = max;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (request.getContentLengthLong() > max) {
                problem(response, HttpStatus.PAYLOAD_TOO_LARGE, "payload_too_large", "the request body is too large");
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
