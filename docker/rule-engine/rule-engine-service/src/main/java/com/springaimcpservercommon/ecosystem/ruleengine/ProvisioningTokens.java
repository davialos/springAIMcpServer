package com.springaimcpservercommon.ecosystem.ruleengine;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.springaimcpservercommon.ruleengine.contract.TokenClaims;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Short-lived tokens this service signs for itself so it can provision the assistant in the library's admin API: two
 * fixed service identities (an author and an approver, because nobody approves their own revision) holding the scope
 * {@code dai.provision}, which the configuration maps to the library's platform administrator role. No user token ever
 * carries that scope, and the signing key is the one the service already has to verify user tokens.
 */
@Component
class ProvisioningTokens {

    /** The scope the library's static role mapping turns into PLATFORM_ADMIN. */
    static final String SCOPE = "dai.provision";
    static final UUID AUTHOR = UUID.nameUUIDFromBytes("rule-assistant:author".getBytes(StandardCharsets.UTF_8));
    static final UUID APPROVER = UUID.nameUUIDFromBytes("rule-assistant:approver".getBytes(StandardCharsets.UTF_8));

    private static final Duration LIFETIME = Duration.ofMinutes(2);

    private final byte[] key;

    ProvisioningTokens(RuleEngineProperties props) {
        this.key = props.jwtSecret().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Signs a token for a provisioning identity.
     *
     * @param subject {@link #AUTHOR} or {@link #APPROVER}
     * @return the compact token (without the {@code Bearer } prefix)
     */
    String mint(UUID subject) {
        Instant now = Instant.now();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
                    .issuer(TokenClaims.ISSUER).subject(subject.toString()).claim("scope", SCOPE)
                    .issueTime(Date.from(now)).expirationTime(Date.from(now.plus(LIFETIME))).build());
            jwt.sign(new MACSigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("cannot sign a provisioning token", e);
        }
    }
}
