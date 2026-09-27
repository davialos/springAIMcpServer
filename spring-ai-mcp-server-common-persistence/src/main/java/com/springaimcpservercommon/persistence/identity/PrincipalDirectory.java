package com.springaimcpservercommon.persistence.identity;

import com.springaimcpservercommon.core.id.Ids;
import com.springaimcpservercommon.core.principal.SubjectType;
import com.springaimcpservercommon.persistence.unit.DaiStore;
import jakarta.persistence.EntityManager;
import org.hibernate.query.NativeQuery;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves external subjects (IdP users/groups, service accounts, MCP clients) to compact {@code dai_principal}
 * ids, creating the row on first sight (SEC-01 §3).
 *
 * <p>{@link #resolve} is called on every authenticated request (behind the security module's cache), so it is one
 * native statement: {@code INSERT … ON CONFLICT (issuer, subject_type, external_id) DO UPDATE … RETURNING}. The
 * update only happens when {@code last_seen_at} is older than the throttle (default 5 minutes) or the display name
 * changed, so steady traffic does not rewrite the row on every request. When the conflicting row is not updated,
 * the same statement returns the existing row through a {@code UNION ALL} branch. Only in the rare race where a
 * concurrent transaction inserted the row after this statement's snapshot is a second, plain {@code SELECT} needed.
 */
public final class PrincipalDirectory {

    /** Default minimum interval between two {@code last_seen_at} updates of the same principal. */
    public static final Duration DEFAULT_LAST_SEEN_THROTTLE = Duration.ofMinutes(5);

    private final DaiStore store;
    private final Clock clock;
    private final Duration lastSeenThrottle;
    private final String upsertSql;
    private final String selectSql;

    /**
     * Creates a directory with the system UTC clock and the default throttle.
     *
     * @param store the persistence unit
     */
    public PrincipalDirectory(DaiStore store) {
        this(store, Clock.systemUTC(), DEFAULT_LAST_SEEN_THROTTLE);
    }

    /**
     * Creates a directory.
     *
     * @param store            the persistence unit
     * @param clock            time source
     * @param lastSeenThrottle minimum interval between {@code last_seen_at} updates (zero = always update)
     */
    public PrincipalDirectory(DaiStore store, Clock clock, Duration lastSeenThrottle) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lastSeenThrottle = Objects.requireNonNull(lastSeenThrottle, "lastSeenThrottle");
        if (lastSeenThrottle.isNegative()) {
            throw new IllegalArgumentException("lastSeenThrottle must not be negative");
        }
        String table = store.schema() + ".dai_principal";
        this.upsertSql = """
                WITH upsert AS (
                    INSERT INTO %1$s AS p (id, subject_type, issuer, external_id, display_name, status,
                                           first_seen_at, last_seen_at)
                    VALUES (?1, ?2, ?3, ?4, ?5, 'ACTIVE', ?6, ?6)
                    ON CONFLICT (issuer, subject_type, external_id) DO UPDATE
                       SET last_seen_at = EXCLUDED.last_seen_at,
                           display_name = coalesce(EXCLUDED.display_name, p.display_name)
                     WHERE p.last_seen_at <= ?7
                        OR (EXCLUDED.display_name IS NOT NULL AND EXCLUDED.display_name IS DISTINCT FROM p.display_name)
                    RETURNING p.id, p.status
                )
                SELECT id, status FROM upsert
                UNION ALL
                SELECT id, status FROM %1$s
                 WHERE issuer = ?3 AND subject_type = ?2 AND external_id = ?4
                   AND NOT EXISTS (SELECT 1 FROM upsert)
                """.formatted(table);
        this.selectSql = "SELECT id, status FROM " + table + " WHERE issuer = ?1 AND subject_type = ?2 AND external_id = ?3";
    }

    /**
     * Returns the id of the principal row for a subject, creating it on first sight.
     *
     * @param type        kind of subject
     * @param issuer      IdP issuer (or {@code dai} for service accounts)
     * @param externalId  stable subject id (sub / oid / DN / group id), never an e-mail address
     * @param displayName optional display name; when given and different it replaces the stored one
     * @return the principal id (the row may be DISABLED, see {@link #resolveSubject})
     */
    public UUID resolve(SubjectType type, String issuer, String externalId, @Nullable String displayName) {
        return resolveSubject(type, issuer, externalId, displayName).id();
    }

    /**
     * Like {@link #resolve} but also returns the principal status, so callers can refuse disabled principals
     * without a second query.
     *
     * @param type        kind of subject
     * @param issuer      IdP issuer
     * @param externalId  stable subject id
     * @param displayName optional display name
     * @return id and status
     */
    public ResolvedPrincipal resolveSubject(SubjectType type, String issuer, String externalId,
                                            @Nullable String displayName) {
        Objects.requireNonNull(type, "type");
        Principal.requireSubjectKey(issuer, externalId);
        Instant now = clock.instant();
        Instant updateIfSeenBefore = now.minus(lastSeenThrottle);
        return store.transactions().execute(status -> {
            EntityManager em = store.entityManager();
            NativeQuery<?> upsert = em.createNativeQuery(upsertSql).unwrap(NativeQuery.class);
            upsert.setParameter(1, Ids.newId(), UUID.class);
            upsert.setParameter(2, type.name(), String.class);
            upsert.setParameter(3, issuer, String.class);
            upsert.setParameter(4, externalId, String.class);
            upsert.setParameter(5, displayName, String.class);
            upsert.setParameter(6, now, Instant.class);
            upsert.setParameter(7, updateIfSeenBefore, Instant.class);
            Optional<ResolvedPrincipal> resolved = firstRow(upsert.getResultList());
            if (resolved.isPresent()) {
                return resolved.get();
            }
            // A concurrent transaction committed the row after this statement's snapshot: read it in a new statement.
            NativeQuery<?> select = em.createNativeQuery(selectSql).unwrap(NativeQuery.class);
            select.setParameter(1, issuer, String.class);
            select.setParameter(2, type.name(), String.class);
            select.setParameter(3, externalId, String.class);
            return firstRow(select.getResultList()).orElseThrow(() ->
                    new IllegalStateException("principal upsert returned no row for issuer " + issuer));
        });
    }

    private static Optional<ResolvedPrincipal> firstRow(List<?> rows) {
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = (Object[]) rows.getFirst();
        UUID id = row[0] instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(row[0]));
        return Optional.of(new ResolvedPrincipal(id, PrincipalStatus.valueOf(String.valueOf(row[1]))));
    }

    /**
     * Looks a principal up by id.
     *
     * @param id principal id
     * @return the principal, if it exists
     */
    public Optional<PrincipalView> find(UUID id) {
        Objects.requireNonNull(id, "id");
        return store.readOnlyTransactions().execute(status ->
                Optional.ofNullable(store.entityManager().find(Principal.class, id)).map(Principal::view));
    }

    /**
     * Looks a principal up by its subject key without creating it.
     *
     * @param type       kind of subject
     * @param issuer     issuer
     * @param externalId external subject id
     * @return the principal, if it exists
     */
    public Optional<PrincipalView> findBySubject(SubjectType type, String issuer, String externalId) {
        Objects.requireNonNull(type, "type");
        Principal.requireSubjectKey(issuer, externalId);
        return store.readOnlyTransactions().execute(status -> store.entityManager()
                .createQuery("select p from Principal p where p.issuer = :issuer and p.subjectType = :type"
                        + " and p.externalId = :externalId", Principal.class)
                .setParameter("issuer", issuer)
                .setParameter("type", type)
                .setParameter("externalId", externalId)
                .getResultStream()
                .findFirst()
                .map(Principal::view));
    }

    /**
     * Disables a principal (e.g. a leaver whose IdP account still authenticates). Rows are never deleted.
     *
     * @param id principal id
     * @return {@code true} if the principal exists
     */
    public boolean disable(UUID id) {
        return changeStatus(id, true);
    }

    /**
     * Re-enables a disabled principal.
     *
     * @param id principal id
     * @return {@code true} if the principal exists
     */
    public boolean enable(UUID id) {
        return changeStatus(id, false);
    }

    private boolean changeStatus(UUID id, boolean disable) {
        Objects.requireNonNull(id, "id");
        Boolean found = store.transactions().execute(status -> {
            Principal principal = store.entityManager().find(Principal.class, id);
            if (principal == null) {
                return false;
            }
            if (disable) {
                principal.disable();
            } else {
                principal.enable();
            }
            return true;
        });
        return Boolean.TRUE.equals(found);
    }
}
