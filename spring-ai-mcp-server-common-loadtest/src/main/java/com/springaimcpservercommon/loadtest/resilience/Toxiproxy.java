package com.springaimcpservercommon.loadtest.resilience;

import com.springaimcpservercommon.loadtest.discovery.Documents;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A client of a Toxiproxy server's REST API (default port 8474): enough to check that it is there before a run, to
 * list its proxies and to leave it clean afterwards. The experiments themselves are injected by the k6 suite.
 */
public final class Toxiproxy {

    private final String base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    /**
     * Creates a client.
     *
     * @param url the server's API address, e.g. {@code http://localhost:8474}
     */
    public Toxiproxy(String url) {
        this.base = url.replaceAll("/+$", "");
    }

    private HttpResponse<String> send(String method, String path, String body) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    /**
     * The server's version.
     *
     * @return the version text, e.g. {@code 2.9.0}
     * @throws IOException when the server cannot be reached or does not answer 2xx
     */
    public String version() throws IOException {
        HttpResponse<String> r = send("GET", "/version", null);
        if (r.statusCode() >= 300) {
            throw new IOException("Toxiproxy " + base + "/version answered HTTP " + r.statusCode());
        }
        return r.body().strip();
    }

    /**
     * The names of the proxies it knows.
     *
     * @return proxy names
     * @throws IOException when the server cannot be reached
     */
    public List<String> proxies() throws IOException {
        HttpResponse<String> r = send("GET", "/proxies", null);
        JsonNode all = Documents.parse(r.body());
        List<String> names = new ArrayList<>();
        all.propertyNames().forEach(names::add);
        return names;
    }

    /**
     * Removes every toxic and enables every proxy again — what a run leaves behind when it is killed mid-fault.
     *
     * @throws IOException when the server cannot be reached or refuses
     */
    public void reset() throws IOException {
        HttpResponse<String> r = send("POST", "/reset", null);
        if (r.statusCode() >= 300) {
            throw new IOException("Toxiproxy reset answered HTTP " + r.statusCode());
        }
    }
}
