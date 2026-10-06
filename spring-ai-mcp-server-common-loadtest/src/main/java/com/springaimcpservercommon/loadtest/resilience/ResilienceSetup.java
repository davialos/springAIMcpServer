package com.springaimcpservercommon.loadtest.resilience;

import com.springaimcpservercommon.loadtest.discovery.Documents;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Prepares a suite for resilience experiments: the {@code resilience} block of {@code loadtest.config.json} (proxies and
 * a ready set of experiments) and a {@code resilience/docker-compose.yml} that starts Toxiproxy next to the application.
 * Traffic then flows {@code k6 → Toxiproxy → application}; dependencies the application calls (database, broker,
 * downstream services) can be put behind their own proxy so their faults are injected too.
 */
public final class ResilienceSetup {

    private ResilienceSetup() {
    }

    /**
     * A dependency of the application that gets its own proxy.
     *
     * @param name       proxy name, e.g. {@code db}
     * @param upstream   where the real service is, as seen from Toxiproxy ({@code host.docker.internal:5432})
     * @param listenPort the port Toxiproxy listens on; the application must be configured to connect to it
     */
    public record Dependency(String name, String upstream, int listenPort) {
    }

    /**
     * What to set up.
     *
     * @param baseUrl      the application's address as the suite has it (path included)
     * @param upstream     the application as seen from Toxiproxy, or {@code null} to derive it from {@code baseUrl}
     * @param listenPort   the port the application's proxy listens on
     * @param dependencies further proxies
     * @param toxiproxyUrl the Toxiproxy API address k6 uses
     * @param image        the Toxiproxy container image
     */
    public record Options(String baseUrl, @Nullable String upstream, int listenPort, List<Dependency> dependencies,
                          String toxiproxyUrl, String image) {

        /**
         * Options with the defaults: proxy on 8666, API on 8474.
         *
         * @param baseUrl the application's address
         * @return options
         */
        public static Options of(String baseUrl) {
            return new Options(baseUrl, null, 8666, List.of(), "http://localhost:8474", "ghcr.io/shopify/toxiproxy:2.9.0");
        }
    }

    /** Where Toxiproxy (in a container) finds the application that runs on the same machine. */
    static String upstreamOf(Options o) {
        if (o.upstream() != null && !o.upstream().isBlank()) {
            return o.upstream();
        }
        URI u = URI.create(o.baseUrl().isBlank() ? "http://localhost:8080" : o.baseUrl());
        String host = u.getHost() == null ? "localhost" : u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : "https".equals(u.getScheme()) ? 443 : 80;
        boolean local = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1");
        return (local ? "host.docker.internal" : host) + ":" + port;
    }

    /** The address k6 uses: the application's proxy, same path. */
    static String proxiedBaseUrl(Options o) {
        URI u = URI.create(o.baseUrl().isBlank() ? "http://localhost:8080" : o.baseUrl());
        String path = u.getRawPath() == null ? "" : u.getRawPath().replaceAll("/+$", "");
        return u.getScheme() + "://localhost:" + o.listenPort() + path;
    }

    /**
     * The {@code resilience} config block: proxies plus experiments (disabled until a run asks for them).
     *
     * @param o what to set up
     * @return the block
     */
    public static ObjectNode block(Options o) {
        ObjectNode r = Documents.json().createObjectNode();
        r.put("enabled", false);
        r.put("toxiproxy", o.toxiproxyUrl());
        r.put("baseUrl", proxiedBaseUrl(o));
        ArrayNode proxies = r.putArray("proxies");
        proxies.addObject().put("name", "app").put("listen", "0.0.0.0:" + o.listenPort()).put("upstream", upstreamOf(o));
        for (Dependency d : o.dependencies()) {
            proxies.addObject().put("name", d.name()).put("listen", "0.0.0.0:" + d.listenPort()).put("upstream", d.upstream());
        }
        ArrayNode ex = r.putArray("experiments");
        // each experiment starts `startAfter` into the measured run, lasts `duration`, then `recovery` is watched
        int at = 30;
        experiment(ex, "slow-network", "app", at, 30, 20, 0.02, 3000)
                .putArray("toxics").addObject().put("type", "latency").put("stream", "downstream")
                .set("attributes", Documents.json().createObjectNode().put("latency", 300).put("jitter", 100));
        at += 30 + 20 + 10;
        ObjectNode narrow = experiment(ex, "narrow-bandwidth", "app", at, 30, 20, 0.2, 10000);
        narrow.putArray("toxics").addObject().put("type", "bandwidth").put("stream", "downstream")
                .set("attributes", Documents.json().createObjectNode().put("rate", 64));
        at += 30 + 20 + 10;
        experiment(ex, "connection-resets", "app", at, 20, 20, 1.0, null)
                .putArray("toxics").addObject().put("type", "reset_peer").put("stream", "downstream").put("toxicity", 0.3)
                .set("attributes", Documents.json().createObjectNode().put("timeout", 200));
        at += 20 + 20 + 10;
        experiment(ex, "outage", "app", at, 20, 30, 1.0, null)
                .putArray("toxics").addObject().put("type", "down");
        at += 20 + 30 + 10;
        for (Dependency d : o.dependencies()) {
            experiment(ex, d.name() + "-latency", d.name(), at, 30, 20, 0.05, 5000)
                    .putArray("toxics").addObject().put("type", "latency").put("stream", "downstream")
                    .set("attributes", Documents.json().createObjectNode().put("latency", 500).put("jitter", 200));
            at += 30 + 20 + 10;
            experiment(ex, d.name() + "-outage", d.name(), at, 20, 30, 1.0, null)
                    .putArray("toxics").addObject().put("type", "down");
            at += 20 + 30 + 10;
        }
        return r;
    }

    private static ObjectNode experiment(ArrayNode into, String name, String proxy, int startAfter, int duration,
                                         int recovery, double maxErrorRate, @Nullable Integer p95Ms) {
        ObjectNode e = into.addObject();
        e.put("name", name).put("proxy", proxy).put("startAfter", startAfter + "s").put("duration", duration + "s")
                .put("recovery", recovery + "s");
        ObjectNode expect = e.putObject("expect").put("maxErrorRate", maxErrorRate);
        if (p95Ms != null) {
            expect.put("p95Ms", p95Ms);
        }
        return e;
    }

    /**
     * The Compose file: Toxiproxy with the proxy ports published, reaching the host's services as
     * {@code host.docker.internal}.
     *
     * @param o what to set up
     * @return YAML
     */
    public static String compose(Options o) {
        StringBuilder y = new StringBuilder("""
                # Toxiproxy next to the application under test (generated by `loadtest resilience-init`).
                #   docker compose -f resilience/docker-compose.yml up -d
                # k6 -> localhost:%d (proxy "app") -> the application. Faults are injected by `loadtest run --resilience`.
                services:
                  toxiproxy:
                    image: %s
                    command: ["-host=0.0.0.0", "-config=/config/toxiproxy.json"]
                    ports:
                      - "8474:8474"          # REST API (the suite creates the proxies here)
                      - "%d:%d"          # proxy "app"
                """.formatted(o.listenPort(), o.image(), o.listenPort(), o.listenPort()));
        for (Dependency d : o.dependencies()) {
            y.append("      - \"").append(d.listenPort()).append(':').append(d.listenPort()).append("\"          # proxy \"")
                    .append(d.name()).append("\"\n");
        }
        y.append("""
                    extra_hosts:
                      - "host.docker.internal:host-gateway"
                    volumes:
                      - ./toxiproxy.json:/config/toxiproxy.json:ro
                """);
        return y.toString();
    }

    /**
     * The Toxiproxy start-up configuration (the same proxies, so the container is usable without the suite too).
     *
     * @param o what to set up
     * @return JSON
     */
    public static String proxiesJson(Options o) {
        return block(o).path("proxies").toPrettyString();
    }

    /**
     * Writes the {@code resilience} block into the suite's config and the Compose files next to it.
     *
     * @param suite   suite directory (holds {@code loadtest.config.json})
     * @param o       what to set up
     * @param replace overwrite a {@code resilience} block that already has experiments
     * @return the written Compose file
     * @throws IOException when the suite cannot be read or written
     */
    public static Path write(Path suite, Options o, boolean replace) throws IOException {
        Path config = suite.resolve("loadtest.config.json");
        if (!Files.exists(config)) {
            throw new IllegalArgumentException(config + " not found (generate the suite first)");
        }
        ObjectNode root = (ObjectNode) Documents.parse(Files.readString(config));
        JsonNode existing = root.path("resilience");
        if (existing.path("experiments").size() > 0 && !replace) {
            throw new IllegalArgumentException("config already has resilience experiments; edit them there or pass --force to regenerate");
        }
        Options effective = o.baseUrl().isBlank() ? new Options(root.path("baseUrl").asString("http://localhost:8080"),
                o.upstream(), o.listenPort(), o.dependencies(), o.toxiproxyUrl(), o.image()) : o;
        root.set("resilience", block(effective));
        Files.writeString(config, root.toPrettyString() + "\n");
        Path dir = Files.createDirectories(suite.resolve("resilience"));
        Files.writeString(dir.resolve("toxiproxy.json"), proxiesJson(effective) + "\n");
        Path compose = dir.resolve("docker-compose.yml");
        Files.writeString(compose, compose(effective));
        return compose;
    }
}
