package com.springaimcpservercommon.ecosystem.ruleengine;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.springaimcpservercommon.ruleengine.contract.TokenClaims;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/** Test support: signs tokens like the auth service does and calls the running service over real HTTP. */
final class Api {

    static final String SECRET = "integration-test-secret-0123456789abcdef-xyz";
    static final UUID ACME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID GLOBEX = UUID.fromString("11111111-1111-1111-1111-111111111112");
    static final UUID RETAIL = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID CORPORATE = UUID.fromString("22222222-2222-2222-2222-222222222223");
    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** A signed-in user as the auth service would describe it. */
    record Persona(String username, UUID userId, boolean admin, UUID tenant, String tenantName, UUID org,
                   String orgName) {

        static final Persona ADMIN = new Persona("admin", UUID.fromString("33333333-0000-0000-0000-000000000001"), true,
                ACME, "Acme Bank", RETAIL, "Retail");
        static final Persona USER = new Persona("user", UUID.fromString("33333333-0000-0000-0000-000000000002"), false,
                ACME, "Acme Bank", RETAIL, "Retail");
        static final Persona CORP = new Persona("corp.user", UUID.fromString("33333333-0000-0000-0000-000000000003"),
                false, ACME, "Acme Bank", CORPORATE, "Corporate");
        static final Persona GLOBEX_ADMIN = new Persona("globex.admin",
                UUID.fromString("33333333-0000-0000-0000-000000000004"), true, GLOBEX, "Globex Corporation", null, null);

        String token() {
            return token(SECRET, 3600);
        }

        String token(String secret, long ttlSeconds) {
            JWTClaimsSet.Builder c = new JWTClaimsSet.Builder().issuer(TokenClaims.ISSUER).subject(userId.toString())
                    .issueTime(Date.from(Instant.now().minusSeconds(10)))
                    .expirationTime(Date.from(Instant.now().plusSeconds(ttlSeconds)))
                    .claim(TokenClaims.USERNAME, username).claim(TokenClaims.DISPLAY_NAME, username + " (test)")
                    .claim(TokenClaims.ROLE, admin ? "ADMIN" : "USER").claim(TokenClaims.TENANT_ID, tenant.toString())
                    .claim(TokenClaims.TENANT_NAME, tenantName);
            if (org != null) {
                c.claim(TokenClaims.ORGANIZATION_ID, org.toString()).claim(TokenClaims.ORGANIZATION_NAME, orgName);
            }
            return sign(secret, c.build());
        }
    }

    static String sign(String secret, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret.getBytes()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A response: status and parsed JSON body (null when empty). */
    record Resp(int status, JsonNode body, String raw) {
        JsonNode at(String pointer) {
            return body.at(pointer);
        }
    }

    private final int port;

    Api(int port) {
        this.port = port;
    }

    Resp call(String method, String path, String bearer, Object body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            if (bearer != null) {
                b.header("Authorization", "Bearer " + bearer);
            }
            String json = body == null ? null : body instanceof String s ? s : JSON.writeValueAsString(body);
            b.header("Content-Type", "application/json");
            b.method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json));
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(r.statusCode(), parse(r.body()), r.body());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The JSON of a body; an event stream or any other non-JSON body has none (the raw text stays in the response). */
    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return JSON.nullNode();
        }
        try {
            return JSON.readTree(body);
        } catch (RuntimeException e) {
            return JSON.nullNode();
        }
    }

    Resp get(String path, Persona p) {
        return call("GET", path, p.token(), null);
    }

    Resp post(String path, Persona p, Object body) {
        return call("POST", path, p.token(), body);
    }
}
