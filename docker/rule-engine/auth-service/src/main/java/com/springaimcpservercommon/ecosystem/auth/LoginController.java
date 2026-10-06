package com.springaimcpservercommon.ecosystem.auth;

import com.springaimcpservercommon.ruleengine.contract.v1.LoginRequest;
import com.springaimcpservercommon.ruleengine.contract.v1.LoginResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * {@code POST /auth/login} takes a protobuf {@code LoginRequest} (or the same fields as JSON) and answers with a
 * {@code LoginResponse}: the signed-in user with the tenant and organization ids, and the access token that signs the
 * same ids. {@code GET /auth/session} answers with the {@code Session} of the bearer token's user.
 */
@RestController
@RequestMapping("/auth")
class LoginController {

    private final LoginService logins;
    private final UserRepository users;

    LoginController(LoginService logins, UserRepository users) {
        this.logins = logins;
        this.users = users;
    }

    @PostMapping("/login")
    ResponseEntity<byte[]> login(@RequestBody(required = false) byte[] body,
                                 @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
                                 @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept,
                                 HttpServletRequest request) {
        boolean protobuf = Wire.replyAsProtobuf(contentType, accept);
        LoginRequest credentials;
        try {
            credentials = Wire.readLogin(body == null ? new byte[0] : body, contentType);
        } catch (IllegalArgumentException e) {
            return Wire.error(400, "invalid_request", e.getMessage(), protobuf);
        }
        try {
            LoginService.Result r = logins.login(credentials.getUsername(), credentials.getPassword(),
                    request.getRemoteAddr());
            return Wire.ok(LoginResponse.newBuilder().setSession(Wire.session(r.user()))
                    .setAccessToken(r.token().token())
                    .setExpiresAtEpochSeconds(r.token().expiresAt().getEpochSecond()).build(), protobuf);
        } catch (LoginService.LoginException e) {
            return Wire.error(e.status().value(), e.code(), e.getMessage(), protobuf);
        }
    }

    @GetMapping("/session")
    ResponseEntity<byte[]> session(@AuthenticationPrincipal Jwt jwt,
                                   @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {
        boolean protobuf = Wire.replyAsProtobuf(null, accept);
        UserAccount user = users.findById(UUID.fromString(jwt.getSubject())).filter(UserAccount::enabled).orElse(null);
        if (user == null) {
            return Wire.error(401, "unknown_user", "the user of this token no longer exists or is disabled",
                    protobuf);
        }
        return Wire.ok(Wire.session(user), protobuf);
    }
}
