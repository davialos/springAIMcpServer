package com.springaimcpservercommon.persistence.audit;

import com.springaimcpservercommon.core.hash.Sha256;
import com.springaimcpservercommon.persistence.unit.DaiPersistenceUnit;
import com.springaimcpservercommon.persistence.unit.PostgresTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Hash-chained audit against PostgreSQL: concurrency, verification and tamper detection. */
class AuditTrailIT {

    private static DaiPersistenceUnit unit;
    private static AuditTrail trail;
    private static UUID actor;

    @BeforeAll
    static void start() {
        unit = PostgresTestSupport.startFreshUnit();
        trail = new AuditTrail(unit, "it-env", Clock.systemUTC());
        actor = PostgresTestSupport.principal(unit.schema());
    }

    @AfterAll
    static void stop() {
        unit.close();
    }

    private static AuditEventDraft toolInvoked(UUID workspace, int n) {
        return new AuditEventDraft(AuditCategory.INVOCATION, "TOOL_INVOKED", AuditPlane.AGENT, actor,
                AuditActorType.USER, null, workspace, "tool", "find_orders", AuditDecision.PERMIT, null, null, null,
                null, null, null, "{\"n\": " + n + ", \"rows\": 3}", null);
    }

    @Test
    void concurrentAppendsToOneChainAreContiguousAndVerify() throws Exception {
        UUID workspace = PostgresTestSupport.workspace(unit.schema());
        int threads = 8;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<List<AppendedAuditEvent>>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int base = t * perThread;
                futures.add(pool.submit(() -> {
                    List<AppendedAuditEvent> appended = new ArrayList<>();
                    for (int i = 0; i < perThread; i++) {
                        appended.add(trail.append(toolInvoked(workspace, base + i)));
                    }
                    return appended;
                }));
            }
            List<Long> seqs = new ArrayList<>();
            for (Future<List<AppendedAuditEvent>> f : futures) {
                f.get().forEach(a -> seqs.add(a.chainSeq()));
            }
            int total = threads * perThread;
            assertThat(seqs).hasSize(total).doesNotHaveDuplicates()
                    .containsExactlyInAnyOrderElementsOf(java.util.stream.LongStream.rangeClosed(1, total).boxed().toList());
        } finally {
            pool.shutdownNow();
        }

        String chain = workspace.toString();
        AuditChain head = trail.head(chain).orElseThrow();
        assertThat(head.getLastSeq()).isEqualTo(threads * perThread);
        ChainVerification verification = trail.verify(chain, 1, head.getLastSeq());
        assertThat(verification.intact()).as(String.valueOf(verification.problem())).isTrue();
        assertThat(verification.eventsChecked()).isEqualTo(threads * perThread);
        assertThat(trail.verify(chain, 100, 150).intact()).isTrue();
    }

    @Test
    void eventsWithoutWorkspaceGoToTheSystemChain() {
        AppendedAuditEvent appended = trail.append(AuditEventDraft.system("PARTITIONS_CREATED", null, "{\"count\":2}"));

        assertThat(appended.chainId()).isEqualTo(AuditTrail.SYSTEM_CHAIN);
        assertThat(trail.head("system").orElseThrow().getLastHash()).isEqualTo(appended.hash());
        assertThat(trail.verify("system", 1, appended.chainSeq()).intact()).isTrue();
    }

    @Test
    void forgedRowInsertedWithSqlIsDetected() {
        UUID workspace = PostgresTestSupport.workspace(unit.schema());
        AppendedAuditEvent first = trail.append(toolInvoked(workspace, 1));
        trail.append(toolInvoked(workspace, 2));
        String chain = workspace.toString();

        // an attacker with INSERT rights appends a row pretending to continue the chain
        JdbcTemplate jdbc = PostgresTestSupport.jdbc();
        jdbc.update("INSERT INTO " + unit.schema() + ".dai_audit_event (id, occurred_at, chain_id, chain_seq, category, "
                        + "action, plane, actor_id, actor_type, workspace_id, decision, environment_id, prev_hash, hash) "
                        + "VALUES (?, ?, ?, 3, 'ADMIN', 'GRANT_CHANGED', 'CONTROL', ?, 'USER', ?, 'PERMIT', 'it-env', ?, ?)",
                UUID.randomUUID(), OffsetDateTime.now(ZoneOffset.UTC), chain, actor, workspace, first.hash(),
                Sha256.of("forged"));

        ChainVerification verification = trail.verify(chain, 1, 3);
        assertThat(verification.intact()).isFalse();
        assertThat(verification.firstBrokenSeq()).isEqualTo(3L);
        assertThat(verification.eventsChecked()).isEqualTo(2);
    }

    @Test
    void auditRowsCannotBeUpdatedOrDeleted() {
        UUID workspace = PostgresTestSupport.workspace(unit.schema());
        AppendedAuditEvent appended = trail.append(toolInvoked(workspace, 1));
        JdbcTemplate jdbc = PostgresTestSupport.jdbc();

        assertThatThrownBy(() -> jdbc.update("UPDATE " + unit.schema() + ".dai_audit_event SET reason = 'x' WHERE id = ?",
                appended.id()))
                .hasRootCauseInstanceOf(SQLException.class)
                .rootCause().satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
        assertThatThrownBy(() -> jdbc.update("DELETE FROM " + unit.schema() + ".dai_audit_event WHERE id = ?",
                appended.id()))
                .hasRootCauseInstanceOf(SQLException.class);
    }

    @Test
    void readsByWorkspaceAndProposal() {
        UUID workspace = PostgresTestSupport.workspace(unit.schema());
        UUID proposal = UUID.randomUUID();
        trail.append(new AuditEventDraft(AuditCategory.DATA_WRITE, "PROPOSAL_CONFIRMED", AuditPlane.AGENT, actor,
                AuditActorType.USER, null, workspace, "proposal", proposal.toString(), AuditDecision.PERMIT, null, null,
                null, null, proposal, null, null, null));

        assertThat(trail.eventsOfProposal(proposal)).singleElement()
                .satisfies(e -> assertThat(e.getAction()).isEqualTo("PROPOSAL_CONFIRMED"));
        assertThat(trail.eventsOfWorkspace(workspace,
                com.springaimcpservercommon.persistence.support.TimeRange.lastUntil(
                        java.time.Instant.now().plusSeconds(60), java.time.Duration.ofDays(1)),
                com.springaimcpservercommon.persistence.support.PageRequest.first(10)).items()).hasSize(1);
    }
}
