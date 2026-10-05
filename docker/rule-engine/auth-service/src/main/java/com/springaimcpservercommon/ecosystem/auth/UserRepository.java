package com.springaimcpservercommon.ecosystem.auth;

import com.springaimcpservercommon.ruleengine.contract.v1.Role;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Reads users and records login attempts in the {@code re_auth} schema. */
@Repository
class UserRepository {

    private static final String SELECT = """
            SELECT u.id, u.tenant_id, t.name AS tenant_name, u.organization_id, o.name AS organization_name,
                   u.username, u.display_name, u.password_hash, u.role, u.enabled
            FROM re_auth_user u
                     JOIN re_auth_tenant t ON t.id = u.tenant_id
                     LEFT JOIN re_auth_organization o ON o.id = u.organization_id
            """;

    private final JdbcClient jdbc;

    UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<UserAccount> findByUsername(String username) {
        return jdbc.sql(SELECT + " WHERE lower(u.username) = lower(:username)").param("username", username)
                .query(UserRepository::map).optional();
    }

    Optional<UserAccount> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE u.id = :id").param("id", id).query(UserRepository::map).optional();
    }

    /** Failed attempts of a user inside the window (counted by login name, so unknown names are throttled too). */
    long recentFailures(String username, Instant since) {
        Long n = jdbc.sql("""
                        SELECT count(*) FROM re_auth_login_event
                        WHERE lower(username) = lower(:username) AND occurred_at >= :since
                          AND outcome IN ('BAD_CREDENTIALS', 'LOCKED')
                          AND occurred_at > coalesce((SELECT max(occurred_at) FROM re_auth_login_event
                                                      WHERE lower(username) = lower(:username) AND outcome = 'SUCCESS'),
                                                     'epoch')
                        """)
                .param("username", username).param("since", java.sql.Timestamp.from(since)).query(Long.class).single();
        return n == null ? 0 : n;
    }

    void recordAttempt(String username, @Nullable UUID userId, String outcome, @Nullable String remoteAddr) {
        jdbc.sql("INSERT INTO re_auth_login_event (username, user_id, outcome, remote_addr) "
                        + "VALUES (:username, :userId, :outcome, :addr)")
                .param("username", truncate(username)).param("userId", userId).param("outcome", outcome)
                .param("addr", remoteAddr).update();
    }

    void touchLastLogin(UUID id) {
        jdbc.sql("UPDATE re_auth_user SET last_login_at = now() WHERE id = :id").param("id", id).update();
    }

    private static String truncate(String s) {
        return s.length() <= 128 ? s : s.substring(0, 128);
    }

    private static UserAccount map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new UserAccount(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("tenant_name"), rs.getObject("organization_id", UUID.class),
                rs.getString("organization_name"), rs.getString("username"), rs.getString("display_name"),
                rs.getString("password_hash"), "ADMIN".equals(rs.getString("role")) ? Role.ROLE_ADMIN : Role.ROLE_USER,
                rs.getBoolean("enabled"));
    }

    /** Convenience for tests and the seeder: the window start for a lockout window. */
    static Instant windowStart(Duration window) {
        return Instant.now().minus(window);
    }
}
