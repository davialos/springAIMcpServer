package com.springaimcpservercommon.ecosystem.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.springaimcpservercommon.ruleengine.contract.TokenClaims;
import com.springaimcpservercommon.ruleengine.contract.v1.Role;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Signs the access token. The token carries the user, role, tenant and organization ids as claims, so the
 * rule-engine service takes its scope from the signature and never from the request.
 */
@Component
class TokenService {

    private final JwtEncoder encoder;
    private final AuthProperties props;

    TokenService(AuthProperties props) {
        this.props = props;
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(
                new SecretKeySpec(props.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
    }

    /** A signed token and when it expires. */
    record Issued(String token, Instant expiresAt) {
    }

    Issued issue(UserAccount user) {
        Instant now = Instant.now();
        Instant expires = now.plus(props.tokenTtl());
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().issuer(TokenClaims.ISSUER).subject(user.id().toString())
                .id(UUID.randomUUID().toString()).issuedAt(now).expiresAt(expires)
                .claim(TokenClaims.USERNAME, user.username()).claim(TokenClaims.DISPLAY_NAME, user.displayName())
                .claim(TokenClaims.ROLE, user.role() == Role.ROLE_ADMIN ? "ADMIN" : "USER")
                .claim(TokenClaims.TENANT_ID, user.tenantId().toString())
                .claim(TokenClaims.TENANT_NAME, user.tenantName());
        if (user.organizationId() != null) {
            claims.claim(TokenClaims.ORGANIZATION_ID, user.organizationId().toString());
            claims.claim(TokenClaims.ORGANIZATION_NAME, user.organizationName());
        }
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                claims.build())).getTokenValue();
        return new Issued(token, expires);
    }
}
