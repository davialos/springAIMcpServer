package com.springaimcpservercommon.ecosystem.auth;

import com.springaimcpservercommon.ruleengine.contract.TokenClaims;
import com.springaimcpservercommon.ruleengine.contract.v1.ErrorResponse;
import com.springaimcpservercommon.ruleengine.contract.v1.LoginRequest;
import com.springaimcpservercommon.ruleengine.contract.v1.LoginResponse;
import com.springaimcpservercommon.ruleengine.contract.v1.Role;
import com.springaimcpservercommon.ruleengine.contract.v1.Session;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The two representations of the contract on the wire: Protocol Buffers ({@code application/x-protobuf}, what the UI
 * speaks) and JSON (what curl and other tools can speak). The reply uses the format the client asked for with
 * {@code Accept}; without a usable {@code Accept} it mirrors the request's {@code Content-Type}.
 */
final class Wire {

    static final MediaType PROTOBUF = MediaType.parseMediaType(TokenClaims.PROTOBUF);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Wire() {
    }

    static boolean isProtobuf(@Nullable String mediaType) {
        return mediaType != null && mediaType.toLowerCase(Locale.ROOT).contains("protobuf");
    }

    /** Whether the reply should be protobuf. */
    static boolean replyAsProtobuf(@Nullable String contentType, @Nullable String accept) {
        if (accept != null && !accept.isBlank() && !accept.contains("*/*")) {
            return isProtobuf(accept);
        }
        return isProtobuf(contentType);
    }

    /** Reads the login request in whichever format it came; a malformed body is an {@link IllegalArgumentException}. */
    static LoginRequest readLogin(byte[] body, @Nullable String contentType) {
        try {
            if (isProtobuf(contentType)) {
                return LoginRequest.parseFrom(body);
            }
            JsonNode n = JSON.readTree(body);
            return LoginRequest.newBuilder().setUsername(text(n, "username")).setPassword(text(n, "password")).build();
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("malformed login request");
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asString();
    }

    static Session session(UserAccount u) {
        Session.Builder b = Session.newBuilder().setUserId(u.id().toString()).setUsername(u.username())
                .setDisplayName(u.displayName()).setRole(u.role()).setTenantId(u.tenantId().toString())
                .setTenantName(u.tenantName());
        if (u.organizationId() != null) {
            b.setOrganizationId(u.organizationId().toString());
            if (u.organizationName() != null) {
                b.setOrganizationName(u.organizationName());
            }
        }
        return b.build();
    }

    static ResponseEntity<byte[]> ok(LoginResponse r, boolean protobuf) {
        return protobuf ? body(200, PROTOBUF, r.toByteArray()) : body(200, MediaType.APPLICATION_JSON, json(Map.of(
                "session", sessionMap(r.getSession()), "accessToken", r.getAccessToken(),
                "expiresAtEpochSeconds", r.getExpiresAtEpochSeconds())));
    }

    static ResponseEntity<byte[]> ok(Session s, boolean protobuf) {
        return protobuf ? body(200, PROTOBUF, s.toByteArray()) : body(200, MediaType.APPLICATION_JSON,
                json(sessionMap(s)));
    }

    static ResponseEntity<byte[]> error(int status, String code, String message, boolean protobuf) {
        return protobuf ? body(status, PROTOBUF, ErrorResponse.newBuilder().setCode(code).setMessage(message)
                .build().toByteArray())
                : body(status, MediaType.APPLICATION_JSON, json(Map.of("code", code, "message", message)));
    }

    private static Map<String, Object> sessionMap(Session s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", s.getUserId());
        m.put("username", s.getUsername());
        m.put("displayName", s.getDisplayName());
        m.put("role", s.getRole() == Role.ROLE_ADMIN ? "ADMIN" : "USER");
        m.put("tenantId", s.getTenantId());
        m.put("tenantName", s.getTenantName());
        m.put("organizationId", s.getOrganizationId().isEmpty() ? null : s.getOrganizationId());
        m.put("organizationName", s.getOrganizationName().isEmpty() ? null : s.getOrganizationName());
        return m;
    }

    private static byte[] json(Object o) {
        return JSON.writeValueAsBytes(o);
    }

    private static ResponseEntity<byte[]> body(int status, MediaType type, byte[] bytes) {
        return ResponseEntity.status(status).contentType(type).header("Cache-Control", "no-store").body(bytes);
    }
}
