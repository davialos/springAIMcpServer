package com.springaimcpservercommon.loadtest.discovery;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Loads JSON/YAML documents from a file or an HTTP(S) URL, with bounded timeouts.
 */
public final class Documents {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private Documents() {
    }

    /**
     * Reads a document as text.
     *
     * @param location file path or {@code http(s)://} URL
     * @param headers  request headers for URLs (e.g. {@code Authorization}); ignored for files
     * @return the text
     */
    public static String text(String location, Map<String, String> headers) {
        if (location.startsWith("http://") || location.startsWith("https://")) {
            try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL).build()) {
                HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(location)).timeout(TIMEOUT).GET()
                        .header("Accept", "application/json, application/yaml;q=0.9, */*;q=0.5");
                headers.forEach(req::header);
                HttpResponse<String> res = client.send(req.build(), HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() / 100 != 2) {
                    throw new IllegalStateException("GET " + location + " returned HTTP " + res.statusCode());
                }
                return res.body();
            } catch (IOException e) {
                throw new UncheckedIOException("GET " + location + " failed: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
        }
        try {
            return Files.readString(Path.of(location));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + location, e);
        }
    }

    /**
     * Parses JSON, or YAML when the text does not start with a JSON object/array.
     *
     * @param text document text
     * @return the tree
     */
    public static JsonNode parse(String text) {
        String t = text.stripLeading();
        return t.startsWith("{") || t.startsWith("[") ? JSON.readTree(t) : YAML.readTree(t);
    }

    /**
     * Shared JSON mapper (not a bean; this module runs outside Spring).
     *
     * @return the mapper
     */
    public static JsonMapper json() {
        return JSON;
    }
}
