package com.springaimcpservercommon.mockdata.simulation;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * Generates HTTP load from virtual threads: one virtual thread per simulated user, each sending requests back to back
 * (optionally rate-capped and ramped up) for a fixed time, and measures every request. Unlike a fire-and-forget loop it
 * counts every failure by kind, reports latency percentiles, can be cancelled, and is bounded by a {@link SimulationGuard}.
 *
 * <p>Thread-safe; one instance can run several simulations. The report never contains request or response data.
 */
public final class LoadSimulator {

    private static final Logger LOG = LoggerFactory.getLogger(LoadSimulator.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_ERROR_SAMPLES = 5;

    private final SimulationGuard guard;

    /**
     * Creates a simulator.
     *
     * @param guard decides whether a simulation may run
     */
    public LoadSimulator(SimulationGuard guard) {
        this.guard = guard;
    }

    /**
     * Runs a simulation and waits for its report.
     *
     * @param spec what to run
     * @return the report
     * @throws SimulationRefusedException when the guard refuses
     */
    public SimulationReport run(SimulationSpec spec) {
        return start(spec).await();
    }

    /**
     * Starts a simulation in the background.
     *
     * @param spec what to run
     * @return a handle to watch, cancel and await it
     * @throws SimulationRefusedException when the guard refuses
     */
    public SimulationHandle start(SimulationSpec spec) {
        guard.check(spec);
        Run run = new Run(spec);
        Thread.ofVirtual().name("loadsim-main").start(run::execute);
        return run;
    }

    private static final class Run implements SimulationHandle {
        private final SimulationSpec spec;
        private final LatencyHistogram latency = new LatencyHistogram();
        private final Map<String, LongAdder> outcomes = new ConcurrentHashMap<>();
        private final Set<String> errorSamples = ConcurrentHashMap.newKeySet();
        private final LongAdder requests = new LongAdder();
        private final LongAdder failures = new LongAdder();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final CompletableFuture<SimulationReport> result = new CompletableFuture<>();
        private final AtomicLong nextSlot = new AtomicLong();
        private final long intervalNanos;
        private volatile @Nullable ExecutorService executor;

        Run(SimulationSpec spec) {
            this.spec = spec;
            this.intervalNanos = spec.maxRequestsPerSecond() == 0 ? 0 : 1_000_000_000L / spec.maxRequestsPerSecond();
        }

        @Override
        public long requestsSoFar() {
            return requests.sum();
        }

        @Override
        public long failuresSoFar() {
            return failures.sum();
        }

        @Override
        public void cancel() {
            cancelled.set(true);
            ExecutorService e = executor;
            if (e != null) {
                e.shutdownNow();
            }
        }

        @Override
        public CompletableFuture<SimulationReport> result() {
            return result;
        }

        void execute() {
            long started = System.nanoTime();
            long deadline = started + spec.duration().toNanos();
            nextSlot.set(started);
            try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
                executor = vt;
                HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).executor(vt)
                        .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
                List<Future<?>> users = new ArrayList<>();
                for (int i = 0; i < spec.virtualUsers(); i++) {
                    long startAt = started + spec.rampUp().toNanos() * i / spec.virtualUsers();
                    users.add(vt.submit(() -> virtualUser(client, startAt, deadline)));
                }
                // the executor also runs the HttpClient's own tasks: it must stay open until every user is done
                long patience = spec.duration().toSeconds() + spec.rampUp().toSeconds() + spec.requestTimeout().toSeconds() + 30;
                for (Future<?> user : users) {
                    try {
                        user.get(patience, TimeUnit.SECONDS);
                    } catch (CancellationException e) {
                        // cancelled
                    } catch (ExecutionException e) {
                        LOG.warn("A virtual user stopped: {}", e.getCause().getClass().getSimpleName());
                    } catch (TimeoutException e) {
                        cancel();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        cancel();
                    }
                }
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
                return;
            }
            result.complete(report(Duration.ofNanos(System.nanoTime() - started)));
        }

        private void virtualUser(HttpClient client, long startAt, long deadline) {
            sleepUntil(startAt);
            while (!cancelled.get() && System.nanoTime() < deadline) {
                if (intervalNanos > 0 && !pace(deadline)) {
                    return;
                }
                sendOne(client);
            }
        }

        /** Shared pacing: each request claims the next slot; returns false when the slot lies after the deadline. */
        private boolean pace(long deadline) {
            long now = System.nanoTime();
            long slot = nextSlot.getAndUpdate(s -> Math.max(s, now - intervalNanos) + intervalNanos);
            slot = Math.max(slot, now - intervalNanos);
            if (slot >= deadline) {
                return false;
            }
            sleepUntil(slot);
            return true;
        }

        private static void sleepUntil(long nanoTime) {
            long wait;
            while ((wait = nanoTime - System.nanoTime()) > 0 && !Thread.currentThread().isInterrupted()) {
                LockSupport.parkNanos(wait);
            }
        }

        private void sendOne(HttpClient client) {
            HttpRequest request;
            try {
                request = build();
            } catch (RuntimeException e) {
                requests.increment();
                failure("payload-error", "payload-error: " + e.getClass().getSimpleName());
                return;
            }
            long t0 = System.nanoTime();
            String outcome;
            boolean ok = false;
            String sample = null;
            try {
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                int code = response.statusCode();
                outcome = code / 100 + "xx";
                ok = code < 400;
                if (!ok) {
                    sample = "HTTP " + code;
                }
            } catch (HttpTimeoutException e) {
                outcome = "timeout";
                sample = "timeout after " + spec.requestTimeout();
            } catch (IOException e) {
                outcome = "error:" + e.getClass().getSimpleName();
                sample = outcome;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return; // cancelled: the interrupted request is not counted
            }
            latency.record((System.nanoTime() - t0) / 1000);
            requests.increment();
            outcomes.computeIfAbsent(outcome, k -> new LongAdder()).increment();
            if (!ok) {
                failures.increment();
                if (sample != null && errorSamples.size() < MAX_ERROR_SAMPLES) {
                    errorSamples.add(sample);
                }
            }
        }

        private void failure(String outcome, String sample) {
            failures.increment();
            outcomes.computeIfAbsent(outcome, k -> new LongAdder()).increment();
            if (errorSamples.size() < MAX_ERROR_SAMPLES) {
                errorSamples.add(sample);
            }
        }

        private HttpRequest build() {
            HttpRequest.Builder b = HttpRequest.newBuilder(spec.target()).timeout(spec.requestTimeout());
            spec.headers().forEach(b::header);
            if (spec.payload() == null) {
                return b.method(spec.method(), HttpRequest.BodyPublishers.noBody()).build();
            }
            Object payload = spec.payload().get();
            String body = payload instanceof String s ? s : JSON.writeValueAsString(payload);
            if (spec.headers().keySet().stream().noneMatch(k -> k.equalsIgnoreCase("content-type"))) {
                b.header("Content-Type", "application/json");
            }
            return b.method(spec.method(), HttpRequest.BodyPublishers.ofString(body)).build();
        }

        private SimulationReport report(Duration elapsed) {
            Map<String, Long> counts = new TreeMap<>();
            outcomes.forEach((k, v) -> counts.put(k, v.sum()));
            long total = requests.sum();
            double seconds = Math.max(0.001, elapsed.toNanos() / 1e9);
            LOG.info("Load simulation finished: {} requests, {} failures, {} s", total, failures.sum(), Math.round(seconds));
            return new SimulationReport(total, total - failures.sum(), failures.sum(), counts, elapsed, total / seconds,
                    latency.percentile(50), latency.percentile(90), latency.percentile(95), latency.percentile(99), latency.max(),
                    errorSamples.stream().sorted().toList(), cancelled.get());
        }
    }
}
