package com.springaimcpservercommon.loadtest.observe;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Scrapes the target's Prometheus endpoint every few seconds while a run is in progress (once before it starts and
 * once after it ends), for {@link ServerChecks}. It needs no Prometheus server: the application's own
 * {@code /actuator/prometheus} is enough.
 */
public final class ServerProbe {

    private final URI uri;
    private final Map<String, String> headers;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final List<ServerChecks.Snapshot> snapshots = new ArrayList<>();
    private final Consumer<String> log;
    private volatile boolean running = true;
    private @Nullable Thread thread;

    private ServerProbe(URI uri, Map<String, String> headers, Consumer<String> log) {
        this.uri = uri;
        this.headers = headers;
        this.log = log;
    }

    /**
     * Starts sampling if the endpoint answers.
     *
     * @param url      the metrics URL ({@code http://host:8080/actuator/prometheus})
     * @param headers  request headers (e.g. {@code Authorization})
     * @param interval time between scrapes
     * @param log      notes
     * @return the probe, or empty when the endpoint is unreachable or not Prometheus text
     */
    public static Optional<ServerProbe> start(String url, Map<String, String> headers, Duration interval,
                                              Consumer<String> log) {
        ServerProbe probe = new ServerProbe(URI.create(url), headers, log);
        if (!probe.scrape()) {
            return Optional.empty();
        }
        probe.thread = Thread.ofVirtual().name("server-probe").start(() -> {
            while (probe.running) {
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    return;
                }
                if (probe.running) {
                    probe.scrape();
                }
            }
        });
        return Optional.of(probe);
    }

    private boolean scrape() {
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET();
            headers.forEach(req::header);
            HttpResponse<String> res = client.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                log.accept("server checks: " + uri + " answered HTTP " + res.statusCode());
                return false;
            }
            List<PrometheusText.Sample> samples = PrometheusText.parse(res.body());
            if (samples.isEmpty()) {
                log.accept("server checks: " + uri + " returned no Prometheus samples");
                return false;
            }
            synchronized (snapshots) {
                snapshots.add(new ServerChecks.Snapshot(Instant.now(), samples));
            }
            return true;
        } catch (IOException e) {
            log.accept("server checks: cannot read " + uri + ": " + e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Takes a last scrape and stops sampling.
     *
     * @return all scrapes in time order
     */
    public List<ServerChecks.Snapshot> stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(Duration.ofSeconds(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        scrape();
        synchronized (snapshots) {
            return List.copyOf(snapshots);
        }
    }
}
