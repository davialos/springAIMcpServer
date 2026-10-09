package com.springaimcpservercommon.celfaker.importer;

import com.springaimcpservercommon.celfaker.payload.JsonValues;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a Swagger / OpenAPI document from a URL. Given the address of a service or of its Swagger UI, it also tries the
 * usual document locations. Only {@code http}/{@code https}, 10 s timeout, 10 MiB cap; the developer chooses the address.
 */
public final class SpecFetcher {

    private static final List<String> COMMON = List.of("/v3/api-docs", "/v2/api-docs", "/openapi.json", "/swagger.json",
            "/openapi.yaml", "/openapi.yml", "/swagger.yaml", "/api-docs", "/swagger/v1/swagger.json", "/docs/openapi.json",
            "/v3/api-docs.yaml", "/api/openapi.json");
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final YAMLMapper YAML = YAMLMapper.builder().build();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private SpecFetcher() {
    }

    /**
     * Result of a fetch.
     *
     * @param document the parsed document
     * @param source   the URL it came from (the base URL for relative {@code servers} is derived from it)
     */
    public record Fetched(JsonNode document, String source) {
    }

    /**
     * Fetches a document, trying common locations when the address itself is not one.
     *
     * @param address URL of the document, of the service or of its Swagger UI
     * @return the document
     * @throws IllegalArgumentException when no document is found
     */
    public static Fetched fetch(String address) {
        URI uri = parseUri(address);
        List<String> tried = new ArrayList<>();
        List<URI> candidates = new ArrayList<>();
        candidates.add(uri);
        String origin = uri.getScheme() + "://" + uri.getRawAuthority();
        String ctx = uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("/(swagger-ui(/index)?\\.html|swagger-ui/?|docs/?|index\\.html)$", "").replaceAll("/+$", "");
        for (String c : COMMON) {
            candidates.add(URI.create(origin + ctx + c));
            if (!ctx.isEmpty()) {
                candidates.add(URI.create(origin + c));
            }
        }
        for (URI c : candidates) {
            try {
                JsonNode doc = parse(get(c));
                if (isSpec(doc)) {
                    return new Fetched(doc, c.toString());
                }
                tried.add(c.getRawPath());
            } catch (IOException | InterruptedException | RuntimeException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                tried.add(c.getRawPath());
            }
        }
        throw new IllegalArgumentException("no Swagger / OpenAPI document found at " + address + " (tried " + String.join(", ", tried) + ")");
    }

    /**
     * Parses pasted JSON or YAML.
     *
     * @param text the document text
     * @return the document
     * @throws IllegalArgumentException when it is not a Swagger / OpenAPI document
     */
    public static JsonNode parse(String text) {
        String t = text.strip();
        JsonNode doc = t.startsWith("{") ? JsonValues.MAPPER.readTree(t) : YAML.readTree(t);
        if (!isSpec(doc)) {
            throw new IllegalArgumentException("not a Swagger 2 / OpenAPI 3 document (missing \"swagger\" or \"openapi\")");
        }
        return doc;
    }

    private static boolean isSpec(JsonNode doc) {
        return doc != null && doc.isObject() && (doc.has("openapi") || doc.has("swagger")) && doc.has("paths");
    }

    private static URI parseUri(String address) {
        URI uri = URI.create(address.strip().contains("://") ? address.strip() : "http://" + address.strip());
        if (uri.getHost() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
            throw new IllegalArgumentException("only http and https addresses are supported: " + address);
        }
        return uri;
    }

    private static String get(URI uri) throws IOException, InterruptedException {
        HttpResponse<java.io.InputStream> res = CLIENT.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json, application/yaml, */*").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            res.body().close();
            throw new IOException("HTTP " + res.statusCode());
        }
        try (java.io.InputStream in = res.body()) {
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw new IOException("document larger than 10 MiB");
            }
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
