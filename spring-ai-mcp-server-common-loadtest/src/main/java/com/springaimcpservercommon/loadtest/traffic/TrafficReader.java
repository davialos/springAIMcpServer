package com.springaimcpservercommon.loadtest.traffic;

import com.springaimcpservercommon.loadtest.discovery.Documents;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads production traffic into a {@link TrafficModel}: Micrometer's {@code http_server_requests_seconds_*} metrics
 * (Prometheus text from {@code /actuator/prometheus}, or the JSON of Prometheus' query API) and access logs (Apache/
 * nginx common and combined format, or one JSON object per line). Metrics give the endpoint mix, error rates and
 * latency (a histogram gives p95); logs additionally give the arrival rate and how sessions move from one endpoint to
 * the next. Nothing about users or values is kept — only counts, rates and endpoint names.
 */
public final class TrafficReader {

    private static final Pattern SAMPLE = Pattern.compile(
            "^(\\w+)\\{(.*)}\\s+([-+0-9.eE]+|NaN|[+-]Inf)\\s*(?:\\d+)?$"); // labels may hold braces: uri="/a/{id}"
    private static final Pattern LABEL = Pattern.compile("(\\w+)=\"([^\"]*)\"");
    private static final Pattern CLF = Pattern.compile(
            "^(\\S+) \\S+ (\\S+) \\[([^\\]]+)] \"(\\w+) (\\S+)[^\"]*\" (\\d{3}) \\S+(?: \"[^\"]*\" \"[^\"]*\")?(?:\\s+(\\d+(?:\\.\\d+)?))?.*$");
    private static final DateTimeFormatter CLF_TIME = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z",
            Locale.ENGLISH);
    private static final Duration SESSION_GAP = Duration.ofMinutes(30);
    private static final int MAX_DURATION_SAMPLES = 50_000;
    private static final double MIN_TRANSITION = 0.01;

    private TrafficReader() {
    }

    // ── Prometheus ─────────────────────────────────────────────────────────────────────────────────────

    private static final class Counter {
        long requests;
        long serverErrors;
        long clientErrors;
        double sumSeconds;
        final TreeMap<Double, Double> buckets = new TreeMap<>();
    }

    /**
     * Reads Prometheus data: the text exposition format or the JSON of {@code /api/v1/query}.
     *
     * @param text          exposition text or query-API JSON (instant vector of counts per {@code method}, {@code uri},
     *                      {@code status}, optionally {@code __name__} {@code http_server_requests_seconds_count|sum|bucket})
     * @param routes        the suite's APIs
     * @param periodSeconds how long the counters cover (their increase), to derive a rate; {@code 0} = unknown
     * @return the model
     */
    public static TrafficModel fromPrometheus(String text, List<Route> routes, double periodSeconds) {
        Route.Index index = new Route.Index(routes);
        Map<String, Counter> perApi = new LinkedHashMap<>();
        long unmatched = 0;
        List<Sample> samples = text.stripLeading().startsWith("{") ? jsonSamples(text) : textSamples(text);
        for (Sample s : samples) {
            String name = s.name().isEmpty() ? "http_server_requests_seconds_count" : s.name();
            if (!name.startsWith("http_server_requests_seconds")) {
                continue;
            }
            String uri = s.labels().getOrDefault("uri", "");
            String method = s.labels().getOrDefault("method", "");
            if (uri.isEmpty() || method.isEmpty() || uri.equals("NOT_FOUND") || uri.equals("REDIRECTION")
                    || uri.equals("root") || uri.contains("**")) {
                if (name.endsWith("_count")) {
                    unmatched += (long) s.value();
                }
                continue;
            }
            Route route = index.matchTemplate(method, uri).orElse(null);
            if (route == null) {
                if (name.endsWith("_count")) {
                    unmatched += (long) s.value();
                }
                continue;
            }
            Counter c = perApi.computeIfAbsent(route.id(), k -> new Counter());
            if (name.endsWith("_count")) {
                c.requests += (long) s.value();
                String status = s.labels().getOrDefault("status", "");
                if (status.startsWith("5")) {
                    c.serverErrors += (long) s.value();
                } else if (status.startsWith("4")) {
                    c.clientErrors += (long) s.value();
                }
            } else if (name.endsWith("_sum")) {
                c.sumSeconds += s.value();
            } else if (name.endsWith("_bucket")) {
                String le = s.labels().get("le");
                if (le != null) {
                    double bound = le.equals("+Inf") ? Double.POSITIVE_INFINITY : Double.parseDouble(le);
                    c.buckets.merge(bound, s.value(), Double::sum);
                }
            }
        }
        Map<String, TrafficModel.ApiTraffic> apis = new LinkedHashMap<>();
        long total = perApi.values().stream().mapToLong(c -> c.requests).sum();
        perApi.forEach((id, c) -> {
            if (c.requests > 0) {
                apis.put(id, new TrafficModel.ApiTraffic(c.requests, (double) c.requests / Math.max(1, total),
                        (double) c.serverErrors / c.requests, (double) c.clientErrors / c.requests,
                        c.sumSeconds > 0 ? c.sumSeconds / c.requests * 1000 : null, histogramP95(c.buckets)));
            }
        });
        double rate = periodSeconds > 0 ? total / periodSeconds : 0;
        return new TrafficModel(total, unmatched, rate, 0, apis, Map.of(), Map.of(), 0);
    }

    private record Sample(String name, Map<String, String> labels, double value) {
    }

    private static List<Sample> textSamples(String text) {
        List<Sample> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            Matcher m = SAMPLE.matcher(line.strip());
            if (!line.startsWith("#") && m.matches()) {
                Map<String, String> labels = new HashMap<>();
                Matcher lm = LABEL.matcher(m.group(2));
                while (lm.find()) {
                    labels.put(lm.group(1), lm.group(2));
                }
                double v = parse(m.group(3));
                if (Double.isFinite(v) || m.group(1).endsWith("_bucket")) {
                    out.add(new Sample(m.group(1), labels, v));
                }
            }
        }
        return out;
    }

    private static List<Sample> jsonSamples(String json) {
        List<Sample> out = new ArrayList<>();
        JsonNode root = Documents.parse(json);
        JsonNode result = root.path("data").path("result");
        for (JsonNode r : result) {
            Map<String, String> labels = new HashMap<>();
            r.path("metric").properties().forEach(e -> labels.put(e.getKey(), e.getValue().asString()));
            JsonNode value = r.path("value");
            double v = value.isArray() && value.size() == 2 ? parse(value.get(1).asString()) : Double.NaN;
            if (Double.isFinite(v)) {
                out.add(new Sample(labels.getOrDefault("__name__", ""), labels, v));
            }
        }
        return out;
    }

    private static double parse(String s) {
        return switch (s) {
            case "+Inf", "Inf" -> Double.POSITIVE_INFINITY;
            case "-Inf" -> Double.NEGATIVE_INFINITY;
            case "NaN" -> Double.NaN;
            default -> Double.parseDouble(s);
        };
    }

    /** The 95th percentile from cumulative histogram buckets (linear inside the bucket), in ms. */
    private static @Nullable Double histogramP95(TreeMap<Double, Double> buckets) {
        if (buckets.size() < 2) {
            return null;
        }
        double total = buckets.lastEntry().getValue();
        if (total <= 0) {
            return null;
        }
        double target = total * 0.95;
        double prevBound = 0;
        double prevCount = 0;
        for (Map.Entry<Double, Double> b : buckets.entrySet()) {
            if (b.getValue() >= target) {
                if (Double.isInfinite(b.getKey())) {
                    return prevBound * 1000;
                }
                double span = b.getValue() - prevCount;
                double frac = span <= 0 ? 1 : (target - prevCount) / span;
                return (prevBound + (b.getKey() - prevBound) * frac) * 1000;
            }
            prevBound = b.getKey();
            prevCount = b.getValue();
        }
        return null;
    }

    // ── access logs ────────────────────────────────────────────────────────────────────────────────────

    private record Hit(@Nullable Instant at, String who, Route route, int status, @Nullable Double ms) {
    }

    /**
     * Reads an access log.
     *
     * @param lines    log lines: common/combined format (an optional trailing request time) or JSON objects
     *                 ({@code method|request_method}, {@code path|uri|url|request_uri}, {@code status},
     *                 {@code time|@timestamp|ts}, {@code duration|request_time|rt|elapsed}, {@code user|remote_user|
     *                 remote_addr|client_ip})
     * @param routes   the suite's APIs
     * @param basePath context path to strip from logged paths, or {@code null}
     * @param timeUnit unit of a logged duration: {@code ms}, {@code s}, or {@code auto} (a decimal is seconds, an
     *                 integer milliseconds)
     * @return the model
     */
    public static TrafficModel fromAccessLog(Iterable<String> lines, List<Route> routes, @Nullable String basePath,
                                             String timeUnit) {
        Route.Index index = new Route.Index(routes);
        List<Hit> hits = new ArrayList<>();
        long unmatched = 0;
        String prefix = basePath == null ? "" : basePath.replaceAll("/+$", "");
        for (String line : lines) {
            Parsed p = line.startsWith("{") ? json(line, timeUnit) : clf(line, timeUnit);
            if (p == null) {
                continue;
            }
            String path = p.path();
            int q = path.indexOf('?');
            path = q >= 0 ? path.substring(0, q) : path;
            if (!prefix.isEmpty() && path.startsWith(prefix)) {
                path = path.substring(prefix.length());
            }
            Route route = index.match(p.method(), path.isEmpty() ? "/" : path).orElse(null);
            if (route == null) {
                unmatched++;
            } else {
                hits.add(new Hit(p.at(), p.who(), route, p.status(), p.ms()));
            }
        }
        return model(hits, unmatched);
    }

    private record Parsed(@Nullable Instant at, String who, String method, String path, int status,
                          @Nullable Double ms) {
    }

    private static @Nullable Parsed clf(String line, String unit) {
        Matcher m = CLF.matcher(line);
        if (!m.matches()) {
            return null;
        }
        Instant at = null;
        try {
            at = OffsetDateTime.parse(m.group(3), CLF_TIME).toInstant();
        } catch (DateTimeParseException e) {
            // no timestamp: counts still work, rates and sessions do not
        }
        String who = m.group(2).equals("-") ? m.group(1) : m.group(2);
        return new Parsed(at, who, m.group(4), m.group(5), Integer.parseInt(m.group(6)),
                m.group(7) == null ? null : toMs(m.group(7), unit));
    }

    private static @Nullable Parsed json(String line, String unit) {
        JsonNode n;
        try {
            n = Documents.parse(line);
        } catch (RuntimeException e) {
            return null;
        }
        String method = first(n, "method", "request_method", "verb");
        String path = first(n, "path", "uri", "url", "request_uri", "request_path");
        String status = first(n, "status", "response_status", "status_code");
        if (method == null || path == null || status == null) {
            return null;
        }
        String who = first(n, "user", "remote_user", "user_id", "remote_addr", "client_ip", "ip");
        String time = first(n, "time", "@timestamp", "timestamp", "ts");
        String dur = first(n, "duration", "request_time", "rt", "elapsed", "latency");
        Instant at = null;
        if (time != null) {
            try {
                at = time.matches("\\d+(\\.\\d+)?") ? Instant.ofEpochMilli((long) (Double.parseDouble(time)
                        * (time.length() > 11 ? 1 : 1000))) : OffsetDateTime.parse(time).toInstant();
            } catch (DateTimeParseException | NumberFormatException e) {
                // keep without a timestamp
            }
        }
        try {
            return new Parsed(at, who == null ? "?" : who, method, path, Integer.parseInt(status.replaceAll("\\D", "")),
                    dur == null || !dur.matches("\\d+(\\.\\d+)?") ? null : toMs(dur, unit));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static @Nullable String first(JsonNode n, String... names) {
        for (String name : names) {
            JsonNode v = n.path(name);
            if (!v.isMissingNode() && !v.isNull() && !v.isContainer()) {
                return v.asString();
            }
        }
        return null;
    }

    private static double toMs(String value, String unit) {
        double v = Double.parseDouble(value);
        return switch (unit) {
            case "ms" -> v;
            case "s" -> v * 1000;
            default -> value.contains(".") ? v * 1000 : v;
        };
    }

    private static TrafficModel model(List<Hit> hits, long unmatched) {
        Map<String, List<Hit>> byApi = new LinkedHashMap<>();
        for (Hit h : hits) {
            byApi.computeIfAbsent(h.route().id(), k -> new ArrayList<>()).add(h);
        }
        long total = hits.size();
        Map<String, TrafficModel.ApiTraffic> apis = new LinkedHashMap<>();
        byApi.forEach((id, list) -> {
            long server = list.stream().filter(h -> h.status() >= 500).count();
            long client = list.stream().filter(h -> h.status() >= 400 && h.status() < 500).count();
            List<Double> durations = list.stream().map(Hit::ms).filter(d -> d != null).limit(MAX_DURATION_SAMPLES).sorted().toList();
            Double mean = durations.isEmpty() ? null : durations.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            Double p95 = durations.isEmpty() ? null : durations.get(Math.min(durations.size() - 1, (int) Math.floor(durations.size() * 0.95)));
            apis.put(id, new TrafficModel.ApiTraffic(list.size(), (double) list.size() / Math.max(1, total),
                    (double) server / list.size(), (double) client / list.size(), mean, p95));
        });
        // arrival rate: average over the covered time, peak = busiest minute
        double average = 0;
        double peak = 0;
        List<Instant> times = hits.stream().map(Hit::at).filter(t -> t != null).sorted().toList();
        if (times.size() > 1) {
            double span = Math.max(1, Duration.between(times.getFirst(), times.getLast()).toMillis() / 1000.0);
            average = times.size() / span;
            Map<Long, Integer> perMinute = new HashMap<>();
            times.forEach(t -> perMinute.merge(t.getEpochSecond() / 60, 1, Integer::sum));
            peak = span >= 120 ? Collections.max(perMinute.values()) / 60.0 : average;
        }
        // sessions: per user/ip, a gap of 30 minutes starts a new one
        Map<String, List<Hit>> byWho = new LinkedHashMap<>();
        for (Hit h : hits) {
            if (h.at() != null) {
                byWho.computeIfAbsent(h.who(), k -> new ArrayList<>()).add(h);
            }
        }
        Map<String, Double> entry = new LinkedHashMap<>();
        Map<String, Map<String, Double>> transitions = new LinkedHashMap<>();
        int sessions = 0;
        for (List<Hit> list : byWho.values()) {
            list.sort((a, b) -> a.at().compareTo(b.at()));
            String previous = null;
            Instant last = null;
            for (Hit h : list) {
                String id = h.route().id();
                if (previous == null || Duration.between(last, h.at()).compareTo(SESSION_GAP) > 0) {
                    if (previous != null) {
                        transitions.computeIfAbsent(previous, k -> new LinkedHashMap<>()).merge("$end", 1.0, Double::sum);
                    }
                    sessions++;
                    entry.merge(id, 1.0, Double::sum);
                } else {
                    transitions.computeIfAbsent(previous, k -> new LinkedHashMap<>()).merge(id, 1.0, Double::sum);
                }
                previous = id;
                last = h.at();
            }
            if (previous != null) {
                transitions.computeIfAbsent(previous, k -> new LinkedHashMap<>()).merge("$end", 1.0, Double::sum);
            }
        }
        return new TrafficModel(total, unmatched, average, peak, apis, normalize(entry, 0), normalizeAll(transitions),
                sessions);
    }

    private static Map<String, Double> normalize(Map<String, Double> counts, double minimum) {
        double sum = counts.values().stream().mapToDouble(Double::doubleValue).sum();
        Map<String, Double> out = new LinkedHashMap<>();
        if (sum <= 0) {
            return out;
        }
        counts.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).forEach(e -> {
            double p = e.getValue() / sum;
            if (p >= minimum) {
                out.put(e.getKey(), p);
            }
        });
        // pruned probabilities are renormalised
        double kept = out.values().stream().mapToDouble(Double::doubleValue).sum();
        out.replaceAll((k, v) -> v / kept);
        return out;
    }

    private static Map<String, Map<String, Double>> normalizeAll(Map<String, Map<String, Double>> transitions) {
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        transitions.forEach((from, to) -> out.put(from, normalize(to, MIN_TRANSITION)));
        return out;
    }
}
