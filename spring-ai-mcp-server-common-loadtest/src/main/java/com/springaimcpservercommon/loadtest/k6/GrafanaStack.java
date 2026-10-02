package com.springaimcpservercommon.loadtest.k6;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes {@code grafana/} into a suite: a Docker Compose stack (Prometheus with the remote-write receiver k6 streams
 * to, scraping the application's {@code /actuator/prometheus}; Grafana with a provisioned datasource) and a
 * dashboard showing k6 per API next to the application's Micrometer metrics (HTTP server, HikariCP, JVM).
 * <p>
 * The dashboard is regenerated every time; the compose file, {@code prometheus.yml} and the provisioning files
 * are created once and then belong to the team.
 */
public final class GrafanaStack {

    private static final List<String> TEAM_FILES = List.of("docker-compose.yml", "prometheus.yml",
            "provisioning/datasources/prometheus.yml", "provisioning/dashboards/dashboards.yml");
    private static final String DASHBOARD = "dashboards/k6-load-test.json";

    private GrafanaStack() {
    }

    /**
     * Where Prometheus scrapes the application.
     *
     * @param scheme      {@code http} or {@code https}
     * @param target      {@code host:port} as seen from the Prometheus container
     * @param metricsPath path of the Prometheus endpoint
     */
    public record Scrape(String scheme, String target, String metricsPath) {
    }

    /**
     * The scrape target for an application reached at {@code baseUrl}: local hosts become
     * {@code host.docker.internal}; {@code management.server.port} / {@code management.server.base-path} /
     * {@code management.endpoints.web.base-path} from the project's properties are honoured.
     *
     * @param baseUrl    target base URL including the servlet context path
     * @param properties the project's flattened Spring properties
     * @return the scrape settings
     */
    public static Scrape scrape(String baseUrl, Map<String, String> properties) {
        URI uri = URI.create(baseUrl);
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "localhost" : uri.getHost();
        if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("0.0.0.0") || host.equals("::1")
                || host.equals("[::1]")) {
            host = "host.docker.internal";
        }
        int port = uri.getPort() > 0 ? uri.getPort() : scheme.equals("https") ? 443 : 80;
        String contextPath = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
        String actuator = property(properties, "management.endpoints.web.base-path", "/actuator");
        String managementPort = properties.get("management.server.port");
        String prefix = contextPath;
        if (managementPort != null && managementPort.matches("\\d+") && Integer.parseInt(managementPort) != port) {
            port = Integer.parseInt(managementPort); // separate management server: no servlet context path
            prefix = property(properties, "management.server.base-path", "");
        }
        String path = (prefix + "/" + actuator + "/prometheus").replaceAll("/{2,}", "/");
        return new Scrape(scheme, host + ":" + port, path.startsWith("/") ? path : "/" + path);
    }

    private static String property(Map<String, String> properties, String key, String fallback) {
        String v = properties.get(key);
        return v == null || v.isBlank() || v.contains("${") ? fallback : v.replaceAll("/+$", "");
    }

    /**
     * Writes or refreshes {@code <suite>/grafana}.
     *
     * @param suite   suite directory
     * @param project project name (dashboard title)
     * @param scrape  where Prometheus finds the application
     * @return the dashboard file
     */
    public static Path write(Path suite, String project, Scrape scrape) {
        Path dir = suite.resolve("grafana");
        String title = "Load test — " + project;
        String slug = project.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        Map<String, String> values = Map.of(
                "${TITLE}", title,
                "${UID}", truncate("k6-" + (slug.isEmpty() ? "app" : slug), 40),
                "${COMPOSE_NAME}", "loadtest-" + (slug.isEmpty() ? "app" : slug),
                "${SCHEME}", scrape.scheme(),
                "${SCRAPE_TARGET}", scrape.target(),
                "${METRICS_PATH}", scrape.metricsPath());
        try {
            for (String f : TEAM_FILES) {
                Path target = dir.resolve(f);
                if (!Files.exists(target)) {
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, render(f, values, false));
                }
            }
            Path dashboard = dir.resolve(DASHBOARD);
            Files.createDirectories(dashboard.getParent());
            Files.writeString(dashboard, render(DASHBOARD, values, true));
            return dashboard;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + dir, e);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String render(String resource, Map<String, String> values, boolean json) throws IOException {
        String text;
        try (InputStream in = GrafanaStack.class.getResourceAsStream("/loadtest/grafana/" + resource)) {
            if (in == null) {
                throw new IllegalStateException("missing resource /loadtest/grafana/" + resource);
            }
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (var e : values.entrySet()) {
            text = text.replace(e.getKey(), json ? jsonEscape(e.getValue()) : e.getValue());
        }
        return text;
    }

    private static String jsonEscape(@Nullable String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
