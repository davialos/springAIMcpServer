package com.springaimcpservercommon.persistence.identity;

import com.springaimcpservercommon.core.principal.SubjectType;
import com.springaimcpservercommon.persistence.config.support.DaiTestDatabase;
import com.springaimcpservercommon.persistence.config.support.MutableClock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class PrincipalDirectoryIT {

    private static final String ISSUER = "https://login.example.test/tenant";

    private static DaiTestDatabase db;
    private static MutableClock clock;
    private static PrincipalDirectory directory;

    @BeforeAll
    static void setUp() {
        db = DaiTestDatabase.create();
        clock = new MutableClock(Instant.parse("2026-09-28T08:00:00Z"));
        directory = new PrincipalDirectory(db.store(), clock, Duration.ofMinutes(5));
    }

    @AfterAll
    static void tearDown() {
        db.close();
    }

    private static Instant lastSeen(UUID id) {
        Timestamp ts = db.jdbc().queryForObject("SELECT last_seen_at FROM " + db.table("dai_principal")
                + " WHERE id = ?", Timestamp.class, id);
        return ts.toInstant();
    }

    @Test
    void resolveIsIdempotentAndCreatesOneRow() {
        UUID first = directory.resolve(SubjectType.USER, ISSUER, "sub-1", "Alice");
        UUID second = directory.resolve(SubjectType.USER, ISSUER, "sub-1", null);

        assertThat(second).isEqualTo(first);
        assertThat(directory.find(first)).hasValueSatisfying(p -> {
            assertThat(p.displayName()).isEqualTo("Alice");
            assertThat(p.status()).isEqualTo(PrincipalStatus.ACTIVE);
        });
        Integer rows = db.jdbc().queryForObject("SELECT count(*) FROM " + db.table("dai_principal")
                + " WHERE external_id = 'sub-1'", Integer.class);
        assertThat(rows).isEqualTo(1);
        // same external id under another subject type is another principal
        assertThat(directory.resolve(SubjectType.GROUP, ISSUER, "sub-1", null)).isNotEqualTo(first);
    }

    @Test
    void lastSeenIsThrottledAndDisplayNameChangesAreApplied() {
        UUID id = directory.resolve(SubjectType.USER, ISSUER, "sub-throttle", "Bob");
        Instant firstSeen = lastSeen(id);

        clock.advance(Duration.ofMinutes(1));
        directory.resolve(SubjectType.USER, ISSUER, "sub-throttle", "Bob");
        assertThat(lastSeen(id)).isEqualTo(firstSeen);

        directory.resolve(SubjectType.USER, ISSUER, "sub-throttle", "Robert");
        assertThat(directory.find(id).orElseThrow().displayName()).isEqualTo("Robert");

        clock.advance(Duration.ofMinutes(6));
        directory.resolve(SubjectType.USER, ISSUER, "sub-throttle", null);
        assertThat(lastSeen(id)).isEqualTo(clock.instant());
    }

    @Test
    void concurrentFirstSightResolvesToOneId() throws Exception {
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<UUID>> tasks = IntStream.range(0, threads).<Callable<UUID>>mapToObj(i -> () -> {
            start.await();
            return directory.resolve(SubjectType.USER, ISSUER, "sub-race", null);
        }).toList();
        List<UUID> ids;
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<UUID>> futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            ids = futures.stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        }
        assertThat(ids).hasSize(threads).containsOnly(ids.getFirst());
        Integer rows = db.jdbc().queryForObject("SELECT count(*) FROM " + db.table("dai_principal")
                + " WHERE external_id = 'sub-race'", Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void disabledPrincipalsAreReportedByResolveSubject() {
        UUID id = directory.resolve(SubjectType.USER, ISSUER, "sub-leaver", null);
        assertThat(directory.disable(id)).isTrue();

        ResolvedPrincipal resolved = directory.resolveSubject(SubjectType.USER, ISSUER, "sub-leaver", null);
        assertThat(resolved.id()).isEqualTo(id);
        assertThat(resolved.active()).isFalse();
        assertThat(directory.findBySubject(SubjectType.USER, ISSUER, "sub-leaver").orElseThrow().status())
                .isEqualTo(PrincipalStatus.DISABLED);

        assertThat(directory.enable(id)).isTrue();
        assertThat(directory.resolveSubject(SubjectType.USER, ISSUER, "sub-leaver", null).active()).isTrue();
        assertThat(directory.disable(UUID.randomUUID())).isFalse();
    }
}
