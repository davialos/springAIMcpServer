package com.springaimcpservercommon.loadtest.observe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for the Prometheus text exposition format ({@code /actuator/prometheus}).
 */
public final class PrometheusText {

    private static final Pattern SAMPLE = Pattern.compile(
            "^([A-Za-z_:][\\w:]*)(?:\\{(.*)})?\\s+([-+0-9.eE]+|NaN|[+-]?Inf)(?:\\s+-?\\d+)?$"); // labels may hold braces
    private static final Pattern LABEL = Pattern.compile("(\\w+)=\"((?:[^\"\\\\]|\\\\.)*)\"");

    private PrometheusText() {
    }

    /**
     * One sample.
     *
     * @param name   metric name, with its {@code _count}/{@code _sum}/{@code _bucket} suffix
     * @param labels label values
     * @param value  the value (infinite for {@code +Inf})
     */
    public record Sample(String name, Map<String, String> labels, double value) {
    }

    /**
     * Parses exposition text; comments, blank lines and unparsable lines are skipped.
     *
     * @param text the response body
     * @return the samples in order
     */
    public static List<Sample> parse(String text) {
        List<Sample> out = new ArrayList<>();
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            Matcher m = SAMPLE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            Map<String, String> labels = new HashMap<>();
            if (m.group(2) != null) {
                Matcher lm = LABEL.matcher(m.group(2));
                while (lm.find()) {
                    labels.put(lm.group(1), lm.group(2));
                }
            }
            out.add(new Sample(m.group(1), labels, number(m.group(3))));
        }
        return out;
    }

    private static double number(String s) {
        return switch (s) {
            case "+Inf", "Inf" -> Double.POSITIVE_INFINITY;
            case "-Inf" -> Double.NEGATIVE_INFINITY;
            case "NaN" -> Double.NaN;
            default -> Double.parseDouble(s);
        };
    }
}
