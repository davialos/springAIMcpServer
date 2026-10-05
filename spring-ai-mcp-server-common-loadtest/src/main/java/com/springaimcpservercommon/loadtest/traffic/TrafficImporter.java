package com.springaimcpservercommon.loadtest.traffic;

import com.springaimcpservercommon.loadtest.discovery.Documents;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Applies a {@link TrafficModel} to a generated suite: the observed endpoint mix becomes the {@code weight} of every
 * API (what production never called gets weight 0 and drops out of {@code mixed-} runs), the arrival rate and its
 * peak shape the {@code production} profile, optional latency/error baselines become per-API thresholds, and
 * {@code data/traffic.json} keeps the session transitions for {@code session-<profile>} runs.
 */
public final class TrafficImporter {

    private static final double MAX_PEAK_FACTOR = 5;

    private TrafficImporter() {
    }

    /**
     * Import options.
     *
     * @param applySlo     turn the observed p95 and server error rate into per-API thresholds
     * @param sloHeadroom  multiplier on the observed p95 for the threshold (default 1.5: the test environment is rarely
     *                     as fast as production)
     * @param rate         requests per second to use instead of the observed average, or {@code null}
     */
    public record Options(boolean applySlo, double sloHeadroom, @Nullable Double rate) {

        /**
         * Defaults: no thresholds from production latency, observed rate.
         *
         * @return options
         */
        public static Options defaults() {
            return new Options(false, 1.5, null);
        }
    }

    /**
     * The APIs of a suite, as traffic is matched against them.
     *
     * @param suite suite directory
     * @return the routes from {@code loadtest.config.json → apis}
     */
    public static List<Route> routes(Path suite) {
        JsonNode config = readConfig(suite);
        List<Route> out = new ArrayList<>();
        for (var e : config.path("apis").properties()) {
            String path = e.getValue().path("path").asString("");
            if (!path.isEmpty()) {
                out.add(new Route(e.getKey(), e.getValue().path("method").asString("GET"), path));
            }
        }
        return out;
    }

    /**
     * Writes the model into the suite.
     *
     * @param suite   suite directory
     * @param model   observed traffic
     * @param source  where it came from (kept in the config for the record)
     * @param options options
     * @param log     progress lines
     */
    public static void apply(Path suite, TrafficModel model, String source, Options options, Consumer<String> log) {
        ObjectNode config = (ObjectNode) readConfig(suite);
        ObjectNode apis = (ObjectNode) config.path("apis");
        int observed = 0;
        for (var e : apis.properties()) {
            ObjectNode api = (ObjectNode) e.getValue();
            TrafficModel.ApiTraffic t = model.apis().get(e.getKey());
            if (t == null) {
                api.put("weight", 0); // production never called it: out of the mixed traffic
                continue;
            }
            observed++;
            api.put("weight", Math.max(1, (int) Math.round(t.share() * 1000)));
            if (options.applySlo()) {
                if (t.p95Ms() != null) {
                    api.put("p95Ms", (int) Math.max(10, Math.ceil(t.p95Ms() * options.sloHeadroom() / 10) * 10));
                }
                api.put("maxErrorRate", Math.max(0.01, Math.round(t.serverErrorRate() * 2 * 1000) / 1000.0));
            }
        }
        double average = options.rate() != null ? options.rate() : model.averageRate();
        double peakFactor = average > 0 && model.peakRate() > average
                ? Math.min(MAX_PEAK_FACTOR, Math.round(model.peakRate() / average * 100) / 100.0) : 1;
        ObjectNode traffic = config.putObject("traffic");
        traffic.put("source", source);
        traffic.put("importedAt", Instant.now().toString());
        traffic.put("requests", model.totalRequests());
        traffic.put("coverage", Math.round(model.coverage() * 1000) / 1000.0);
        traffic.put("averageRate", Math.round(average * 100) / 100.0);
        traffic.put("peakRate", Math.round(model.peakRate() * 100) / 100.0);
        traffic.put("peakFactor", peakFactor);
        traffic.put("slo", options.applySlo());
        JsonNode production = config.path("modes").path("production");
        if (average > 0 && production instanceof ObjectNode p) {
            p.put("baseRate", Math.max(1, Math.round(average)));
            ArrayNode stages = p.putArray("stages");
            stages.addObject().put("duration", "2m").put("target", 1.0);
            stages.addObject().put("duration", "8m").put("target", 1.0);
            if (peakFactor > 1) {
                stages.addObject().put("duration", "2m").put("target", peakFactor);
                stages.addObject().put("duration", "3m").put("target", peakFactor);
            }
            stages.addObject().put("duration", "1m").put("target", 0.0);
        }
        write(suite.resolve("loadtest.config.json"), config);

        ObjectNode file = Documents.json().createObjectNode();
        file.put("importedAt", Instant.now().toString());
        file.put("source", source);
        file.put("requests", model.totalRequests());
        file.put("averageRate", average);
        file.put("peakRate", model.peakRate());
        ObjectNode perApi = file.putObject("apis");
        model.apis().forEach((id, t) -> {
            ObjectNode n = perApi.putObject(id);
            n.put("requests", t.requests());
            n.put("share", t.share());
            n.put("serverErrorRate", t.serverErrorRate());
            n.put("clientErrorRate", t.clientErrorRate());
            if (t.meanMs() != null) {
                n.put("meanMs", t.meanMs());
            }
            if (t.p95Ms() != null) {
                n.put("p95Ms", t.p95Ms());
            }
        });
        if (!model.transitions().isEmpty()) {
            file.putObject("sessions").put("count", model.sessions()).put("maxSteps", 30);
            file.set("entry", Documents.json().valueToTree(model.entry()));
            file.set("transitions", Documents.json().valueToTree(model.transitions()));
        }
        write(suite.resolve("data/traffic.json"), file);
        log.accept("traffic: " + model.totalRequests() + " requests over " + observed + " of " + apis.size()
                + " APIs (" + Math.round(model.coverage() * 100) + "% of the log matched), average "
                + Math.round(average * 100) / 100.0 + " req/s" + (peakFactor > 1 ? ", peak x" + peakFactor : "")
                + (model.sessions() > 0 ? ", " + model.sessions() + " sessions" : ""));
    }

    private static JsonNode readConfig(Path suite) {
        Path file = suite.resolve("loadtest.config.json");
        if (!Files.exists(file)) {
            throw new IllegalArgumentException(suite + " has no loadtest.config.json (generate the suite first)");
        }
        try {
            return Documents.parse(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path file, JsonNode node) {
        try {
            Files.writeString(file, node.toPrettyString() + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
