package com.springaimcpservercommon.ecosystem.auth;

import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Checks credentials. Unknown user and wrong password are indistinguishable to the caller (same error, same work: a
 * hash comparison is always made), repeated failures lock the name out for a while, and every attempt is recorded
 * without its password.
 */
@Service
class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    /** Hash compared against when the user does not exist, so timing does not reveal which names are real. */
    private final String decoyHash;
    private final PasswordEncoder encoder;
    private final UserRepository users;
    private final TokenService tokens;
    private final AuthProperties props;
    private final MeterRegistry meters;

    LoginService(PasswordEncoder encoder, UserRepository users, TokenService tokens, AuthProperties props,
                 MeterRegistry meters) {
        this.encoder = encoder;
        this.users = users;
        this.tokens = tokens;
        this.props = props;
        this.meters = meters;
        this.decoyHash = encoder.encode("decoy-" + System.nanoTime());
    }

    /** A successful login. */
    record Result(UserAccount user, TokenService.Issued token) {
    }

    /** A refused login: stable code, HTTP status, and a message safe to show. */
    static final class LoginException extends RuntimeException {
        private final String code;
        private final HttpStatus status;

        LoginException(String code, HttpStatus status, String message) {
            super(message);
            this.code = code;
            this.status = status;
        }

        String code() {
            return code;
        }

        HttpStatus status() {
            return status;
        }
    }

    Result login(@Nullable String username, @Nullable String password, @Nullable String remoteAddr) {
        String name = username == null ? "" : username.strip();
        if (name.isEmpty() || password == null || password.isEmpty()) {
            throw new LoginException("invalid_request", HttpStatus.BAD_REQUEST, "username and password are required");
        }
        if (users.recentFailures(name, UserRepository.windowStart(props.lockoutWindow())) >= props.maxFailures()) {
            users.recordAttempt(name, null, "LOCKED", remoteAddr);
            count("LOCKED");
            throw new LoginException("locked", HttpStatus.TOO_MANY_REQUESTS,
                    "too many failed attempts; try again later");
        }
        UserAccount user = users.findByUsername(name).orElse(null);
        boolean matches = encoder.matches(password, user == null ? decoyHash : user.passwordHash());
        if (user == null || !matches) {
            users.recordAttempt(name, user == null ? null : user.id(), "BAD_CREDENTIALS", remoteAddr);
            count("BAD_CREDENTIALS");
            throw new LoginException("bad_credentials", HttpStatus.UNAUTHORIZED, "wrong username or password");
        }
        if (!user.enabled()) {
            users.recordAttempt(name, user.id(), "DISABLED", remoteAddr);
            count("DISABLED");
            throw new LoginException("bad_credentials", HttpStatus.UNAUTHORIZED, "wrong username or password");
        }
        users.recordAttempt(name, user.id(), "SUCCESS", remoteAddr);
        users.touchLastLogin(user.id());
        count("SUCCESS");
        log.info("login ok user={} role={} tenant={}", user.username(), user.role(), user.tenantId());
        return new Result(user, tokens.issue(user));
    }

    private void count(String outcome) {
        meters.counter("ecosystem.auth.login", "outcome", outcome).increment();
    }

    /** The encoder the service uses (BCrypt). */
    static PasswordEncoder defaultEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
