package com.springaimcpservercommon.ruleengine.channel;

import com.springaimcpservercommon.ruleengine.model.Action;
import com.springaimcpservercommon.ruleengine.model.ApiEndpoint;
import com.springaimcpservercommon.ruleengine.store.OutboxItem;
import com.springaimcpservercommon.ruleengine.store.OutboxStore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Drains the delivery outbox: claims due rows, sends each through the e-mail / push / API port, and either marks it
 * delivered (recipient scrubbed) or schedules the next attempt with exponential back-off and jitter. After
 * {@code max_attempts} or on a permanent failure (endpoint removed, environment guard refusal) the row is dead and
 * waits for an operator ({@link OutboxStore#retry}). Safe to run on every node at once ({@code SKIP LOCKED}); a node
 * that dies leaves a lease that expires. Errors are recorded as a short code, never a message (it could carry the
 * recipient). Not thread-safe per instance; run one loop per worker.
 */
public final class OutboxWorker {

    /**
     * Worker tuning.
     *
     * @param batchSize         rows claimed per run
     * @param lease             how long a claim lasts before another node may take the row
     * @param baseDelay         delay after the first failure
     * @param maxDelay          cap of the exponential delay
     * @param deliveredRetention how long delivered rows are kept (they hold no personal data)
     * @param deadRetention      how long dead rows are kept for operators
     */
    public record Settings(int batchSize, Duration lease, Duration baseDelay, Duration maxDelay,
                           Duration deliveredRetention, Duration deadRetention) {

        /** Defaults: 20 rows, 2 min lease, 10 s doubling up to 1 h, 1 day / 14 days retention. */
        public static Settings defaults() {
            return new Settings(20, Duration.ofMinutes(2), Duration.ofSeconds(10), Duration.ofHours(1),
                    Duration.ofDays(1), Duration.ofDays(14));
        }
    }

    /**
     * What one run did.
     *
     * @param claimed   rows claimed
     * @param delivered rows delivered
     * @param retried   rows scheduled for another attempt
     * @param dead      rows that are now dead
     */
    public record RunResult(int claimed, int delivered, int retried, int dead) {
    }

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration PURGE_EVERY = Duration.ofMinutes(1);

    private final OutboxStore outbox;
    private final @Nullable EmailSender email;
    private final @Nullable PushSender push;
    private final ApiCaller api;
    private final ApiEnvironmentPolicy policy;
    private final Clock clock;
    private final String workerId;
    private final Settings settings;
    private Instant lastPurge = Instant.EPOCH;

    /**
     * Creates the worker.
     *
     * @param outbox   the outbox
     * @param email    mail port, or {@code null} (rows then retry until a sender exists)
     * @param push     push port, or {@code null}
     * @param api      HTTP port
     * @param policy   API environment guard (checked again at send time)
     * @param clock    time source
     * @param workerId id recorded on claimed rows
     * @param settings tuning
     */
    public OutboxWorker(OutboxStore outbox, @Nullable EmailSender email, @Nullable PushSender push, ApiCaller api,
                        ApiEnvironmentPolicy policy, Clock clock, String workerId, Settings settings) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.email = email;
        this.push = push;
        this.api = Objects.requireNonNull(api, "api");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * Claims and sends one batch, then purges old rows (at most once a minute).
     *
     * @return what happened
     */
    public RunResult runOnce() {
        Instant now = clock.instant();
        List<OutboxItem> items = outbox.claim(workerId, settings.batchSize(), settings.lease());
        int delivered = 0;
        int retried = 0;
        int dead = 0;
        for (OutboxItem item : items) {
            Outcome outcome = send(item);
            switch (outcome.kind) {
                case DELIVERED -> {
                    outbox.delivered(item.id());
                    delivered++;
                }
                case PERMANENT -> {
                    outbox.failed(item.id(), outcome.code, Duration.ZERO, true);
                    dead++;
                }
                case TRANSIENT -> {
                    boolean last = item.attempts() >= item.maxAttempts();
                    outbox.failed(item.id(), outcome.code, backoff(item.attempts(), settings.baseDelay(),
                            settings.maxDelay(), ThreadLocalRandom.current().nextDouble()), false);
                    if (last) {
                        dead++;
                    } else {
                        retried++;
                    }
                }
            }
        }
        if (Duration.between(lastPurge, now).compareTo(PURGE_EVERY) >= 0) {
            lastPurge = now;
            int purged = outbox.purge(settings.deliveredRetention(), settings.deadRetention());
            if (purged > 0) {
                log.debug("rule-engine outbox purged {} rows", purged);
            }
        }
        if (!items.isEmpty()) {
            log.info("rule-engine outbox: claimed {}, delivered {}, retry {}, dead {}", items.size(), delivered,
                    retried, dead);
        }
        return new RunResult(items.size(), delivered, retried, dead);
    }

    /**
     * Delay before the next attempt: {@code base * 2^(attempt-1)} capped at {@code max}, spread by ±20% jitter.
     *
     * @param attempt attempts made so far (1 = the first failure)
     * @param base    delay after the first failure
     * @param max     cap
     * @param random  a value in [0, 1) that picks the jitter (injectable for tests)
     * @return the delay
     */
    public static Duration backoff(int attempt, Duration base, Duration max, double random) {
        long baseMillis = base.toMillis();
        int shift = Math.min(Math.max(attempt - 1, 0), 30);
        long exponential = Math.min(baseMillis * (1L << shift), max.toMillis());
        double jitter = 0.8 + 0.4 * random;
        return Duration.ofMillis(Math.max(1, Math.round(exponential * jitter)));
    }

    private enum Kind { DELIVERED, TRANSIENT, PERMANENT }

    private record Outcome(Kind kind, String code) {
        static Outcome ok() {
            return new Outcome(Kind.DELIVERED, "");
        }

        static Outcome retry(String code) {
            return new Outcome(Kind.TRANSIENT, code);
        }

        static Outcome dead(String code) {
            return new Outcome(Kind.PERMANENT, code);
        }
    }

    private Outcome send(OutboxItem item) {
        try {
            return switch (item.channelType()) {
                case EMAIL -> sendEmail(item);
                case PUSH -> sendPush(item);
                case API -> sendApi(item);
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.retry("INTERRUPTED");
        } catch (Exception e) {
            return Outcome.retry(e.getClass().getSimpleName());
        }
    }

    private Outcome sendEmail(OutboxItem item) throws Exception {
        if (email == null) {
            return Outcome.retry("NO_EMAIL_SENDER");
        }
        JsonNode n = JSON.readTree(item.payloadJson());
        email.send(new EmailMessage(n.path("templateRef").asString(), n.path("templateName").asString(),
                n.path("recipient").asString(), n.path("language").asString(), n.path("module").asString(),
                n.path("group").asString(), Action.valueOf(n.path("decision").asString())));
        return Outcome.ok();
    }

    private Outcome sendPush(OutboxItem item) throws Exception {
        if (push == null) {
            return Outcome.retry("NO_PUSH_SENDER");
        }
        JsonNode n = JSON.readTree(item.payloadJson());
        push.send(new PushMessage(n.path("recipient").asString(), n.path("title").asString(),
                n.path("body").asString(), n.path("language").asString()));
        return Outcome.ok();
    }

    private Outcome sendApi(OutboxItem item) throws Exception {
        ApiEndpoint endpoint = item.apiEndpointId() == null ? null : outbox.endpoint(item.apiEndpointId());
        if (endpoint == null) {
            return Outcome.dead("ENDPOINT_GONE");
        }
        ApiCheck check = policy.checkForDispatch(endpoint);
        if (!check.allowed()) {
            return Outcome.dead(check.reasonKey());
        }
        api.call(endpoint, item.payloadJson());
        return Outcome.ok();
    }
}
