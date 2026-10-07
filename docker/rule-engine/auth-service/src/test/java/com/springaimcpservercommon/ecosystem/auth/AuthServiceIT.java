package com.springaimcpservercommon.ecosystem.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.springaimcpservercommon.ruleengine.contract.TokenClaims;
import com.springaimcpservercommon.ruleengine.contract.v1.ErrorResponse;
import com.springaimcpservercommon.ruleengine.contract.v1.LoginRequest;
import com.springaimcpservercommon.ruleengine.contract.v1.LoginResponse;
import com.springaimcpservercommon.ruleengine.contract.v1.Role;
import com.springaimcpservercommon.ruleengine.contract.v1.Session;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The auth service end to end: real HTTP, real protobuf bytes, real PostgreSQL. Uses Testcontainers; set
 * {@code ECOSYSTEM_IT_JDBC_URL} (+ {@code ECOSYSTEM_IT_USER}/{@code ECOSYSTEM_IT_PASSWORD}) to use an existing database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthServiceIT {

    private static final String PROTOBUF = TokenClaims.PROTOBUF;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String SECRET = "integration-test-secret-0123456789abcdef-xyz";

    private static PostgreSQLContainer container;
    private static String url;
    private static String user;
    private static String password;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws SQLException {
        url = System.getenv("ECOSYSTEM_IT_JDBC_URL");
        if (url == null) {
            container = new PostgreSQLContainer("postgres:17-alpine");
            container.start();
            url = container.getJdbcUrl();
            user = container.getUsername();
            password = container.getPassword();
        } else {
            user = System.getenv("ECOSYSTEM_IT_USER");
            password = System.getenv("ECOSYSTEM_IT_PASSWORD");
        }
        try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS re_auth CASCADE");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> password == null ? "" : password);
        registry.add("ecosystem.auth.jwt-secret", () -> SECRET);
        registry.add("ecosystem.auth.max-failures", () -> "3");
    }

    @AfterAll
    static void stop() {
        if (container != null) {
            container.stop();
        }
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    JwtDecoder decoder;

    private HttpResponse<byte[]> post(String path, byte[] body, String contentType, String accept)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (accept != null) {
            b.header("Accept", accept);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> loginProtobuf(String username, String password) throws Exception {
        return post("/auth/login", LoginRequest.newBuilder().setUsername(username).setPassword(password).build()
                .toByteArray(), PROTOBUF, PROTOBUF);
    }

    private HttpResponse<byte[]> get(String path, String bearer, String accept) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        if (accept != null) {
            b.header("Accept", accept);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void protobufLoginCarriesTheUserTenantAndOrganizationIds() throws Exception {
        HttpResponse<byte[]> r = loginProtobuf("admin", "admin123");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith(PROTOBUF));
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
        LoginResponse login = LoginResponse.parseFrom(r.body());
        Session s = login.getSession();
        assertThat(s.getUserId()).isEqualTo("33333333-0000-0000-0000-000000000001");
        assertThat(s.getUsername()).isEqualTo("admin");
        assertThat(s.getRole()).isEqualTo(Role.ROLE_ADMIN);
        assertThat(s.getTenantId()).isEqualTo(DevSeed.ACME.toString());
        assertThat(s.getTenantName()).isEqualTo("Acme Bank");
        assertThat(s.getOrganizationId()).isEqualTo(DevSeed.ACME_RETAIL.toString());
        assertThat(s.getOrganizationName()).isEqualTo("Retail");
        assertThat(login.getExpiresAtEpochSeconds()).isGreaterThan(Instant.now().getEpochSecond());
    }

    @Test
    void theAccessTokenSignsTheSameIdsTheSessionCarries() throws Exception {
        LoginResponse login = LoginResponse.parseFrom(loginProtobuf("user", "user123").body());

        Jwt jwt = decoder.decode(login.getAccessToken());

        assertThat(jwt.getSubject()).isEqualTo(login.getSession().getUserId());
        assertThat(jwt.getClaimAsString(TokenClaims.TENANT_ID)).isEqualTo(login.getSession().getTenantId());
        assertThat(jwt.getClaimAsString(TokenClaims.ORGANIZATION_ID)).isEqualTo(login.getSession().getOrganizationId());
        assertThat(jwt.getClaimAsString(TokenClaims.ROLE)).isEqualTo("USER");
        assertThat(jwt.getIssuer()).hasToString(TokenClaims.ISSUER);
        assertThat(jwt.getExpiresAt()).isAfter(Instant.now());
        // no password, hash or secret in the token
        assertThat(login.getAccessToken().split("\\.")).hasSize(3);
        assertThat(jwt.getClaims().toString()).doesNotContain("user123").doesNotContain("password");
    }

    @Test
    void jsonLoginWorksForToolsThatDoNotSpeakProtobuf() throws Exception {
        HttpResponse<byte[]> r = post("/auth/login", "{\"username\":\"Corp.User\",\"password\":\"user123\"}".getBytes(),
                "application/json", "application/json");

        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode n = JSON.readTree(r.body());
        assertThat(n.get("session").get("role").asString()).isEqualTo("USER"); // user names are case-insensitive
        assertThat(n.get("session").get("tenantId").asString()).isEqualTo(DevSeed.ACME.toString());
        assertThat(n.get("session").get("organizationId").asString()).isEqualTo(DevSeed.ACME_CORPORATE.toString());
        assertThat(n.get("accessToken").asString()).isNotBlank();
    }

    @Test
    void aTenantWideUserHasNoOrganizationAndTheTokenHasNoOrgClaim() throws Exception {
        LoginResponse login = LoginResponse.parseFrom(loginProtobuf("globex.admin", "admin123").body());

        assertThat(login.getSession().getTenantId()).isEqualTo(DevSeed.GLOBEX.toString());
        assertThat(login.getSession().getOrganizationId()).isEmpty();
        assertThat(decoder.decode(login.getAccessToken()).getClaims()).doesNotContainKey(TokenClaims.ORGANIZATION_ID);
    }

    @Test
    void wrongPasswordAndUnknownUserAreIndistinguishable() throws Exception {
        HttpResponse<byte[]> wrong = loginProtobuf("admin", "nope");
        HttpResponse<byte[]> unknown = loginProtobuf("nobody.here", "nope");

        assertThat(wrong.statusCode()).isEqualTo(401).isEqualTo(unknown.statusCode());
        ErrorResponse a = ErrorResponse.parseFrom(wrong.body());
        ErrorResponse b = ErrorResponse.parseFrom(unknown.body());
        assertThat(a).isEqualTo(b);
        assertThat(a.getCode()).isEqualTo("bad_credentials");
    }

    @Test
    void repeatedFailuresLockTheNameOutEvenForTheRightPassword() throws Exception {
        String name = "lock." + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("INSERT INTO re_auth_user (id, tenant_id, organization_id, username, display_name, password_hash, role) "
                        + "VALUES (:id, :t, :o, :u, 'Lock Test', :h, 'USER')")
                .param("id", UUID.randomUUID()).param("t", DevSeed.ACME).param("o", DevSeed.ACME_RETAIL)
                .param("u", name).param("h", encoder.encode("right-password")).update();
        for (int i = 0; i < 3; i++) {
            assertThat(loginProtobuf(name, "wrong" + i).statusCode()).isEqualTo(401);
        }

        HttpResponse<byte[]> locked = loginProtobuf(name, "right-password");

        assertThat(locked.statusCode()).isEqualTo(429);
        assertThat(ErrorResponse.parseFrom(locked.body()).getCode()).isEqualTo("locked");
    }

    @Test
    void aSuccessfulLoginClearsEarlierFailures() throws Exception {
        String name = "ok." + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("INSERT INTO re_auth_user (id, tenant_id, username, display_name, password_hash, role) "
                        + "VALUES (:id, :t, :u, 'Ok Test', :h, 'USER')")
                .param("id", UUID.randomUUID()).param("t", DevSeed.ACME).param("u", name)
                .param("h", encoder.encode("right-password")).update();
        assertThat(loginProtobuf(name, "x").statusCode()).isEqualTo(401);
        assertThat(loginProtobuf(name, "y").statusCode()).isEqualTo(401);

        assertThat(loginProtobuf(name, "right-password").statusCode()).isEqualTo(200);
        assertThat(loginProtobuf(name, "z").statusCode()).isEqualTo(401); // counting restarted, not locked
    }

    @Test
    void aDisabledUserCannotSignInAndLooksLikeABadPassword() throws Exception {
        String name = "off." + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("INSERT INTO re_auth_user (id, tenant_id, username, display_name, password_hash, role, enabled) "
                        + "VALUES (:id, :t, :u, 'Off', :h, 'USER', false)")
                .param("id", UUID.randomUUID()).param("t", DevSeed.ACME).param("u", name)
                .param("h", encoder.encode("right-password")).update();

        HttpResponse<byte[]> r = loginProtobuf(name, "right-password");

        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(ErrorResponse.parseFrom(r.body()).getCode()).isEqualTo("bad_credentials");
    }

    @Test
    void malformedAndEmptyRequestsAreRejectedWithoutALoginAttempt() throws Exception {
        long before = jdbc.sql("SELECT count(*) FROM re_auth_login_event").query(Long.class).single();

        HttpResponse<byte[]> garbage = post("/auth/login", new byte[]{(byte) 0xff, (byte) 0xff, 0x01}, PROTOBUF, PROTOBUF);
        HttpResponse<byte[]> empty = loginProtobuf("", "");
        HttpResponse<byte[]> badJson = post("/auth/login", "{not json".getBytes(), "application/json", "application/json");

        assertThat(garbage.statusCode()).isEqualTo(400);
        assertThat(empty.statusCode()).isEqualTo(400);
        assertThat(badJson.statusCode()).isEqualTo(400);
        assertThat(ErrorResponse.parseFrom(garbage.body()).getCode()).isEqualTo("invalid_request");
        assertThat(jdbc.sql("SELECT count(*) FROM re_auth_login_event").query(Long.class).single()).isEqualTo(before);
    }

    @Test
    void everyAttemptIsRecordedButNeverWithAPassword() throws Exception {
        loginProtobuf("admin", "super-secret-attempt");

        long rows = jdbc.sql("SELECT count(*) FROM re_auth_login_event WHERE username = 'admin'").query(Long.class)
                .single();
        String all = jdbc.sql("SELECT string_agg(username || outcome || coalesce(remote_addr, ''), ' ') "
                + "FROM re_auth_login_event").query(String.class).single();

        assertThat(rows).isPositive();
        assertThat(all).doesNotContain("super-secret-attempt");
        assertThat(jdbc.sql("SELECT count(*) FROM re_auth_login_event WHERE outcome = 'BAD_CREDENTIALS'")
                .query(Long.class).single()).isPositive();
    }

    @Test
    void sessionEndpointReturnsTheTokenHoldersSession() throws Exception {
        LoginResponse login = LoginResponse.parseFrom(loginProtobuf("admin", "admin123").body());

        HttpResponse<byte[]> r = get("/auth/session", login.getAccessToken(), PROTOBUF);

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(Session.parseFrom(r.body())).isEqualTo(login.getSession());
    }

    @Test
    void sessionEndpointRejectsMissingTamperedForeignSignedAndExpiredTokens() throws Exception {
        LoginResponse login = LoginResponse.parseFrom(loginProtobuf("user", "user123").body());
        String token = login.getAccessToken();
        String tampered = token.substring(0, token.length() - 4) + (token.endsWith("AAAA") ? "BBBB" : "AAAA");

        assertThat(get("/auth/session", null, PROTOBUF).statusCode()).isEqualTo(401);
        assertThat(get("/auth/session", "garbage", PROTOBUF).statusCode()).isEqualTo(401);
        assertThat(get("/auth/session", tampered, PROTOBUF).statusCode()).isEqualTo(401);
        assertThat(get("/auth/session", forged("another-secret-0123456789abcdef-0123456789", 3600), PROTOBUF)
                .statusCode()).isEqualTo(401);
        assertThat(get("/auth/session", forged(SECRET, -3600), PROTOBUF).statusCode()).isEqualTo(401);
        assertThat(get("/auth/session", forged(SECRET, 3600), PROTOBUF).statusCode()).isEqualTo(200); // control
    }

    /** A token for the admin signed with the given secret, expiring {@code seconds} from now. */
    private String forged(String secret, long seconds) throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(TokenClaims.ISSUER)
                .subject("33333333-0000-0000-0000-000000000001")
                .issueTime(Date.from(Instant.now().minusSeconds(7200)))
                .expirationTime(Date.from(Instant.now().plusSeconds(seconds))).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secret.getBytes()));
        return jwt.serialize();
    }

    @Test
    void anUnsignedAlgNoneTokenIsRejected() throws Exception {
        String header = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes());
        String payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"iss\":\"" + TokenClaims.ISSUER + "\",\"sub\":\"33333333-0000-0000-0000-000000000001\","
                        + "\"exp\":" + (Instant.now().getEpochSecond() + 3600) + "}").getBytes());

        assertThat(get("/auth/session", header + "." + payload + ".", PROTOBUF).statusCode()).isEqualTo(401);
    }

    @Test
    void noOtherEndpointIsExposedWithoutAToken() throws Exception {
        assertThat(get("/auth/users", null, null).statusCode()).isEqualTo(401);
        assertThat(get("/actuator/health", null, null).statusCode()).isEqualTo(200);
        assertThat(get("/actuator/env", null, null).statusCode()).isIn(401, 404);
    }

    @Test
    void aSigningKeyShorterThan32BytesRefusesToStart() {
        assertThatThrownBy(() -> new AuthProperties("short", java.time.Duration.ofHours(1), 5,
                java.time.Duration.ofMinutes(15), new AuthProperties.Seed(false, "a", "b")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
    }
}
