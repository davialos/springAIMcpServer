package com.springaimcpservercommon.ruleengine;

import com.springaimcpservercommon.core.environment.EnvironmentTier;
import com.springaimcpservercommon.ruleengine.cache.RuleCatalogCache;
import com.springaimcpservercommon.ruleengine.channel.ApiEnvironmentPolicy;
import com.springaimcpservercommon.ruleengine.channel.DispatchResult;
import com.springaimcpservercommon.ruleengine.channel.DispatchResult.Status;
import com.springaimcpservercommon.ruleengine.channel.EmailMessage;
import com.springaimcpservercommon.ruleengine.channel.OutboxChannelDelivery;
import com.springaimcpservercommon.ruleengine.channel.OutboxWorker;
import com.springaimcpservercommon.ruleengine.channel.OutboxWorker.RunResult;
import com.springaimcpservercommon.ruleengine.channel.PushMessage;
import com.springaimcpservercommon.ruleengine.model.ChannelType;
import com.springaimcpservercommon.ruleengine.response.ResponseDetail;
import com.springaimcpservercommon.ruleengine.store.JdbcRuleStore;
import com.springaimcpservercommon.ruleengine.store.OutboxItem;
import com.springaimcpservercommon.ruleengine.store.OutboxStore;
import com.springaimcpservercommon.ruleengine.support.RuleEngineTestDatabase;
import com.springaimcpservercommon.ruleengine.support.SampleTenant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The delivery outbox on PostgreSQL: queueing from the engine, retry/back-off, dead letters, leases, concurrency. */
class OutboxIT {

    private static RuleEngineTestDatabase db;

    private final Clock clock = Clock.systemUTC();

    /** The outbox compares against the database clock, so the test moves rows into the past/future with SQL. */
    private static void makeDue() {
        db.execute("UPDATE dai_re_dispatch SET next_attempt_at = now() - interval '1 second' WHERE status = 'PENDING'");
    }

    private static void expireLeases() {
        db.execute("UPDATE dai_re_dispatch SET locked_until = now() - interval '1 second' WHERE locked_until IS NOT NULL");
    }
    private final List<EmailMessage> emails = new CopyOnWriteArrayList<>();
    private final List<PushMessage> pushes = new CopyOnWriteArrayList<>();
    private final List<String> apiBodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger emailFailures = new AtomicInteger();
    private OutboxStore outbox;

    @BeforeAll
    static void setUp() {
        db = RuleEngineTestDatabase.create(true);
    }

    @AfterAll
    static void tearDown() {
        db.close();
    }

    @BeforeEach
    void clean() {
        db.execute("DELETE FROM dai_re_dispatch");
        outbox = new OutboxStore(db.dataSource(), db.schema());
    }

    private OutboxWorker worker(EnvironmentTier tier, int batch, String id) {
        return new OutboxWorker(outbox,
                m -> {
                    if (emailFailures.getAndDecrement() > 0) {
                        throw new IllegalStateException("smtp down for " + m.recipient());
                    }
                    emails.add(m);
                },
                pushes::add, (endpoint, body) -> apiBodies.add(endpoint.name() + " " + body),
                new ApiEnvironmentPolicy(tier), clock, id,
                new OutboxWorker.Settings(batch, Duration.ofMinutes(2), Duration.ofSeconds(10), Duration.ofHours(1),
                        Duration.ofDays(1), Duration.ofDays(14)));
    }

    private List<DispatchResult> evaluateAndQueue(EnvironmentTier tier) {
        var cache = new RuleCatalogCache(new JdbcRuleStore(db.dataSource(), db.schema()), Clock.systemUTC(),
                Duration.ofSeconds(30), 10, "en");
        var engine = new RuleEngine(cache, new OutboxChannelDelivery(outbox, new ApiEnvironmentPolicy(tier), 3),
                EvaluationRecorder.NONE);
        Map<String, Object> facts = Map.of("customer", Map.of("age", 16, "kycStatus", "PENDING", "creditScore", 700,
                "email", "p@example.com"), "loan", Map.of("amount", 1000.0));
        return engine.evaluate(new EvaluationRequest(SampleTenant.TENANT, null, "LOAN", "LOAN_ELIGIBILITY", facts,
                List.of("hi")), ResponseDetail.MESSAGES, true).dispatched();
    }

    private String payloadOf(UUID id) {
        return String.valueOf(db.queryText("SELECT payload::text FROM dai_re_dispatch WHERE id = '" + id + "'"));
    }

    @Test
    void theEngineQueuesInsteadOfSendingAndTheWorkerDeliversAndScrubsTheRecipient() {
        List<DispatchResult> queued = evaluateAndQueue(EnvironmentTier.DEV);

        assertThat(queued).extracting(DispatchResult::status).containsOnly(Status.QUEUED);
        assertThat(emails).as("nothing sent on the caller's thread").isEmpty();
        assertThat(outbox.count("PENDING")).isEqualTo(3);
        assertThat(db.queryText("SELECT string_agg(payload::text, ' ') FROM dai_re_dispatch")).contains("p@example.com");

        RunResult run = worker(EnvironmentTier.DEV, 10, "w1").runOnce();

        assertThat(run).isEqualTo(new RunResult(3, 3, 0, 0));
        assertThat(emails).singleElement().satisfies(m -> assertThat(m.recipient()).isEqualTo("p@example.com"));
        assertThat(pushes).hasSize(1);
        assertThat(apiBodies).singleElement().satisfies(b -> {
            assertThat(b).contains("\"dispatchId\"").contains("\"decision\": \"BLOCK\"");
            assertThat(b).doesNotContain("p@example.com");
        });
        assertThat(outbox.count("DELIVERED")).isEqualTo(3);
        assertThat(db.queryText("SELECT string_agg(payload::text, ' ') FROM dai_re_dispatch"))
                .as("recipient scrubbed once delivered").doesNotContain("p@example.com").isEqualTo("{} {} {}");
    }

    @Test
    void aFailedSendIsRetriedAfterTheBackOffAndNeverRecordsTheRecipientInTheError() {
        evaluateAndQueue(EnvironmentTier.DEV);
        emailFailures.set(1);
        OutboxWorker w = worker(EnvironmentTier.DEV, 10, "w1");

        RunResult first = w.runOnce();
        assertThat(first).isEqualTo(new RunResult(3, 2, 1, 0));
        assertThat(db.queryText("SELECT last_error FROM dai_re_dispatch WHERE channel_type = 'EMAIL'"))
                .isEqualTo("IllegalStateException");

        assertThat(w.runOnce().claimed()).as("not due yet").isZero();
        assertThat(db.queryLong("SELECT extract(epoch FROM next_attempt_at - now())::bigint FROM dai_re_dispatch"
                + " WHERE channel_type = 'EMAIL'")).as("10 s base, jitter at most +20%").isBetween(7L, 12L);
        makeDue();
        assertThat(w.runOnce()).isEqualTo(new RunResult(1, 1, 0, 0));
        assertThat(emails).hasSize(1);
        assertThat(db.queryLong("SELECT attempts FROM dai_re_dispatch WHERE channel_type = 'EMAIL'")).isEqualTo(2);
    }

    @Test
    void afterTheLastAttemptARowIsDeadAndAnOperatorCanRetryIt() {
        evaluateAndQueue(EnvironmentTier.DEV);
        emailFailures.set(100);
        OutboxWorker w = worker(EnvironmentTier.DEV, 10, "w1");
        for (int i = 0; i < 3; i++) {   // maxAttempts = 3
            w.runOnce();
            makeDue();
        }

        assertThat(outbox.count("DEAD")).isEqualTo(1);
        var dead = outbox.deadLetters(SampleTenant.TENANT, 10);
        assertThat(dead).singleElement().satisfies(d -> {
            assertThat(d.channelType()).isEqualTo(ChannelType.EMAIL);
            assertThat(d.attempts()).isEqualTo(3);
            assertThat(d.lastError()).isEqualTo("IllegalStateException");
        });
        assertThat(w.runOnce().claimed()).as("dead rows are not claimed").isZero();

        emailFailures.set(0);
        assertThat(outbox.retry(UUID.randomUUID(), dead.getFirst().id())).as("other tenant").isFalse();
        assertThat(outbox.retry(SampleTenant.TENANT, dead.getFirst().id())).isTrue();
        assertThat(w.runOnce()).isEqualTo(new RunResult(1, 1, 0, 0));
        assertThat(emails).hasSize(1);
    }

    @Test
    void theEnvironmentGuardIsCheckedAgainWhenSending() {
        evaluateAndQueue(EnvironmentTier.DEV);          // queued while the endpoint (DEV) was allowed

        RunResult run = worker(EnvironmentTier.PROD, 10, "w1").runOnce();   // drained by a production node

        assertThat(run.dead()).isEqualTo(1);
        assertThat(apiBodies).isEmpty();
        assertThat(db.queryText("SELECT last_error FROM dai_re_dispatch WHERE channel_type = 'API'"))
                .isEqualTo("api.environment.mismatch");
    }

    @Test
    void aNodeThatDiesLeavesALeaseThatExpires() {
        evaluateAndQueue(EnvironmentTier.DEV);
        List<OutboxItem> claimed = outbox.claim("crashed-node", 10, Duration.ofMinutes(2));
        assertThat(claimed).hasSize(3);

        assertThat(outbox.claim("other", 10, Duration.ofMinutes(2))).as("leased").isEmpty();
        expireLeases();
        List<OutboxItem> again = outbox.claim("other", 10, Duration.ofMinutes(2));
        assertThat(again).hasSize(3).allSatisfy(i -> assertThat(i.attempts()).as("the lost attempt counts").isEqualTo(2));
    }

    @Test
    void concurrentWorkersNeverClaimTheSameRow() throws Exception {
        for (int i = 0; i < 60; i++) {
            outbox.enqueue(UUID.randomUUID(), SampleTenant.TENANT, ChannelType.PUSH, null, null,
                    "{\"recipient\":\"r" + i + "\",\"title\":\"t\",\"body\":\"b\",\"language\":\"en\"}", 3);
        }
        var pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<List<OutboxItem>>> tasks = new ArrayList<>();
            for (int w = 0; w < 4; w++) {
                String id = "w" + w;
                tasks.add(() -> {
                    List<OutboxItem> mine = new ArrayList<>();
                    List<OutboxItem> batch;
                    while (!(batch = outbox.claim(id, 7, Duration.ofMinutes(2))).isEmpty()) {
                        mine.addAll(batch);
                    }
                    return mine;
                });
            }
            Set<UUID> seen = new HashSet<>();
            int total = 0;
            for (Future<List<OutboxItem>> f : pool.invokeAll(tasks)) {
                for (OutboxItem item : f.get()) {
                    seen.add(item.id());
                    total++;
                }
            }
            assertThat(total).isEqualTo(60);
            assertThat(seen).as("no row claimed twice").hasSize(60);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void oldDeliveredAndDeadRowsArePurgedAndPendingRowsAreKept() {
        UUID delivered = UUID.randomUUID();
        UUID dead = UUID.randomUUID();
        UUID pending = UUID.randomUUID();
        for (UUID id : List.of(delivered, dead, pending)) {
            outbox.enqueue(id, SampleTenant.TENANT, ChannelType.PUSH, null, null, "{}", 3);
        }
        db.execute("ALTER TABLE dai_re_dispatch DISABLE TRIGGER trg_re_dispatch_touch");   // let the test backdate updated_at
        db.execute("UPDATE dai_re_dispatch SET status='DELIVERED', updated_at = now() - interval '2 days' WHERE id = '" + delivered + "'");
        db.execute("UPDATE dai_re_dispatch SET status='DEAD', updated_at = now() - interval '20 days' WHERE id = '" + dead + "'");
        db.execute("UPDATE dai_re_dispatch SET updated_at = now() - interval '30 days' WHERE id = '" + pending + "'");

        db.execute("ALTER TABLE dai_re_dispatch ENABLE TRIGGER trg_re_dispatch_touch");
        int purged = outbox.purge(Duration.ofDays(1), Duration.ofDays(14));

        assertThat(purged).isEqualTo(2);
        assertThat(outbox.count("PENDING")).isEqualTo(1);
    }
}
