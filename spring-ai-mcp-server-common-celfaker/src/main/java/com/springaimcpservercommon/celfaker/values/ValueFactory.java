package com.springaimcpservercommon.celfaker.values;

import com.springaimcpservercommon.celfaker.payload.Candidate;
import com.springaimcpservercommon.celfaker.payload.JsonValues;
import com.springaimcpservercommon.ruleengine.model.DataType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Builds the {@link AttributeValues} of each parameter from its sample, deterministically for a seed. */
public final class ValueFactory {

    private static final int POOL = 10;
    private static final Pattern UUID_RE = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern PHONE_RE = Pattern.compile("^\\+?[0-9][0-9 ()-]{6,18}$");
    private static final List<String> FIRST = List.of("Ada", "Grace", "Linus", "Marie", "Alan", "Hedy", "Dennis", "Radia", "Ken", "Barbara", "Tim", "Margaret");
    private static final List<String> LAST = List.of("Lovelace", "Hopper", "Torvalds", "Curie", "Turing", "Lamarr", "Ritchie", "Perlman", "Thompson", "Liskov", "Berners", "Hamilton");
    private static final List<String> CITY = List.of("London", "Paris", "Berlin", "Madrid", "Tokyo", "Toronto", "Sydney", "Mumbai", "Lagos", "Lima");
    private static final List<String> WORDS = List.of("alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel", "india", "juliet");

    private final long seed;

    /**
     * Creates a factory.
     *
     * @param seed seed for reproducible values
     */
    public ValueFactory(long seed) {
        this.seed = seed;
    }

    /**
     * Builds the value map for the candidates.
     *
     * @param candidates parameters
     * @return the attribute value map
     */
    public AttributeValueMap build(List<Candidate> candidates) {
        Map<String, AttributeValues> out = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            out.put(c.celName(), forCandidate(c));
        }
        return new AttributeValueMap(AttributeValueMap.VERSION, seed, out);
    }

    /**
     * Builds the values of one parameter.
     *
     * @param c the candidate
     * @return its values
     */
    public AttributeValues forCandidate(Candidate c) {
        Random rnd = new Random(seed ^ (c.celName().hashCode() * 0x9E3779B97F4A7C15L));
        Object sample = JsonValues.toJava(c.sample());
        Set<Object> valid = new LinkedHashSet<>();
        Set<Object> boundary = new LinkedHashSet<>();
        List<Object> invalid = new ArrayList<>();
        String kind = "GENERIC";
        switch (c.type()) {
            case STRING -> {
                String s = sample instanceof String x ? x : "";
                kind = stringKind(c.attributeCode(), s);
                strings(kind, s, c.attributeCode(), rnd, valid, boundary);
                invalid.addAll(java.util.Arrays.asList(null, 12345L, true, "x".repeat(10_001)));
            }
            case INT -> {
                long s = sample instanceof Number n ? n.longValue() : 0L;
                ints(s, rnd, valid, boundary);
                invalid.addAll(java.util.Arrays.asList("abc", 1.5, null, true, 1e30));
            }
            case DOUBLE -> {
                double s = sample instanceof Number n ? n.doubleValue() : 0.0;
                doubles(s, rnd, valid, boundary);
                invalid.addAll(java.util.Arrays.asList("abc", null, true));
            }
            case BOOL -> {
                valid.add(true);
                valid.add(false);
                if (sample instanceof Boolean b) {
                    valid.add(b);
                }
                invalid.addAll(java.util.Arrays.asList("yes", 1L, null));
            }
            case TIMESTAMP -> {
                Instant s = sample instanceof String x ? Instant.parse(x) : Instant.parse("2025-01-15T10:00:00Z");
                for (int i = 0; i < POOL; i++) {
                    valid.add(s.plusSeconds((long) (i - POOL / 2) * 86_400L + rnd.nextInt(3600)).toString());
                }
                valid.add(s.toString());
                boundary.addAll(List.of("1970-01-01T00:00:00Z", "2038-01-19T03:14:07Z", "9999-12-31T23:59:59Z"));
                invalid.addAll(java.util.Arrays.asList("not-a-date", "2024-13-45T00:00:00Z", 12345L, null));
            }
            case DURATION -> {
                Duration s = sample instanceof String x ? Duration.parse(x) : Duration.ofHours(1);
                valid.add(s.toString());
                for (long m : new long[]{1, 5, 15, 30, 60, 120, 1440, 10_080}) {
                    valid.add(Duration.ofMinutes(m).toString());
                }
                boundary.addAll(List.of("PT0S", "PT1S", "P365D"));
                invalid.addAll(java.util.Arrays.asList("1 hour", "PT", "abc", null));
            }
            case LIST_STRING -> {
                List<?> s = sample instanceof List<?> l ? l : List.of();
                valid.add(new ArrayList<>(s));
                for (int i = 1; i <= 5; i++) {
                    valid.add(pick(WORDS, rnd, i));
                }
                boundary.add(List.of());
                boundary.add(java.util.stream.IntStream.range(0, 50).mapToObj(i -> "item" + i).toList());
                invalid.addAll(java.util.Arrays.asList("x", List.of(1L, "a"), null));
            }
            case LIST_INT -> {
                List<?> s = sample instanceof List<?> l ? l : List.of();
                valid.add(new ArrayList<>(s));
                for (int i = 1; i <= 5; i++) {
                    valid.add(rnd.longs(i, 0, 100).boxed().toList());
                }
                boundary.add(List.of());
                boundary.add(List.of(0L, -1L, 2_147_483_647L));
                invalid.addAll(java.util.Arrays.asList("x", List.of("a"), null));
            }
            case LIST_DOUBLE -> {
                List<?> s = sample instanceof List<?> l ? l : List.of();
                valid.add(new ArrayList<>(s));
                for (int i = 1; i <= 5; i++) {
                    valid.add(rnd.doubles(i, 0, 100).map(d -> Math.round(d * 100) / 100.0).boxed().toList());
                }
                boundary.add(List.of());
                boundary.add(List.of(0.0, -0.5));
                invalid.addAll(java.util.Arrays.asList("x", List.of("a"), null));
            }
            case MAP -> {
                Map<?, ?> s = sample instanceof Map<?, ?> m ? m : Map.of();
                valid.add(new LinkedHashMap<>(s));
                for (int i = 1; i <= 4; i++) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    for (int k = 0; k < i; k++) {
                        m.put("key" + k, WORDS.get(rnd.nextInt(WORDS.size())));
                    }
                    valid.add(m);
                }
                boundary.add(Map.of());
                invalid.addAll(java.util.Arrays.asList("x", List.of(1L), null));
            }
            case ANY -> {
                if (sample != null) {
                    valid.add(sample);
                }
                valid.addAll(List.of("any", 1L, true));
                boundary.add(List.of());
            }
        }
        if (sample != null) {
            valid.add(sample);
        }
        valid.remove(null);
        return new AttributeValues(c.celName(), c.type(), kind, sample, new ArrayList<>(valid), new ArrayList<>(boundary), invalid);
    }

    private static String stringKind(String attribute, String sample) {
        String a = attribute.toLowerCase(Locale.ROOT);
        if (sample.contains("@") || a.contains("email")) {
            return "EMAIL";
        }
        if (UUID_RE.matcher(sample).matches()) {
            return "UUID";
        }
        if (sample.startsWith("http://") || sample.startsWith("https://")) {
            return "URL";
        }
        if (PHONE_RE.matcher(sample).matches() || a.contains("phone")) {
            return "PHONE";
        }
        if (sample.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
            return "DATE";
        }
        if (a.contains("firstname") || a.equals("name") || a.contains("lastname") || a.contains("fullname")) {
            return "NAME";
        }
        if (a.contains("city")) {
            return "CITY";
        }
        return sample.matches("^[A-Z0-9_-]{2,}$") ? "CODE" : "GENERIC";
    }

    private static void strings(String kind, String sample, String attribute, Random rnd, Set<Object> valid, Set<Object> boundary) {
        for (int i = 0; i < POOL; i++) {
            valid.add(switch (kind) {
                case "EMAIL" -> FIRST.get(rnd.nextInt(FIRST.size())).toLowerCase(Locale.ROOT) + "." + LAST.get(rnd.nextInt(LAST.size())).toLowerCase(Locale.ROOT) + rnd.nextInt(1000) + "@example.com";
                case "UUID" -> new UUID(rnd.nextLong(), rnd.nextLong()).toString();
                case "URL" -> "https://example.com/" + WORDS.get(rnd.nextInt(WORDS.size())) + "/" + rnd.nextInt(10_000);
                case "PHONE" -> "+1555" + String.format("%07d", rnd.nextInt(10_000_000));
                case "DATE" -> String.format("20%02d-%02d-%02d", 20 + rnd.nextInt(7), 1 + rnd.nextInt(12), 1 + rnd.nextInt(28));
                case "NAME" -> attribute.toLowerCase(Locale.ROOT).contains("last") ? LAST.get(rnd.nextInt(LAST.size())) : FIRST.get(rnd.nextInt(FIRST.size()));
                case "CITY" -> CITY.get(rnd.nextInt(CITY.size()));
                case "CODE" -> WORDS.get(rnd.nextInt(WORDS.size())).toUpperCase(Locale.ROOT) + "-" + (100 + rnd.nextInt(900));
                default -> WORDS.get(rnd.nextInt(WORDS.size())) + "-" + rnd.nextInt(1000);
            });
        }
        if (!sample.isEmpty()) {
            valid.add(sample);
        }
        boundary.add("");
        boundary.add(" ");
        boundary.add("a");
        boundary.add("x".repeat(255));
        boundary.add("Ünïcödé-日本語");
        boundary.add("quote\" back\\slash");
    }

    private static void ints(long s, Random rnd, Set<Object> valid, Set<Object> boundary) {
        long lo = Math.min(s / 2, s * 2);
        long hi = Math.max(s / 2, s * 2) + 10;
        valid.add(s);
        for (int i = 0; i < POOL; i++) {
            valid.add(lo + (long) (rnd.nextDouble() * (hi - lo + 1)));
        }
        boundary.addAll(List.of(0L, 1L, -1L, s - 1, s + 1, 2_147_483_647L, -2_147_483_648L, 9_007_199_254_740_991L));
    }

    private static void doubles(double s, Random rnd, Set<Object> valid, Set<Object> boundary) {
        double lo = Math.min(s / 2, s * 2);
        double hi = Math.max(s / 2, s * 2) + 10;
        valid.add(s);
        for (int i = 0; i < POOL; i++) {
            valid.add(Math.round((lo + rnd.nextDouble() * (hi - lo)) * 100) / 100.0);
        }
        boundary.addAll(List.of(0.0, 0.01, -0.5, s + 0.5, 1_000_000.0));
    }

    private static List<String> pick(List<String> pool, Random rnd, int n) {
        List<String> copy = new ArrayList<>(pool);
        java.util.Collections.shuffle(copy, rnd);
        return new ArrayList<>(copy.subList(0, Math.min(n, copy.size())));
    }
}
