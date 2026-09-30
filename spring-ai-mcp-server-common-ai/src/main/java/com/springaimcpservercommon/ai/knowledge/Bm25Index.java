package com.springaimcpservercommon.ai.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keyword ranking (BM25) over a pack's chunks; needs no embedding model. Immutable once built. */
final class Bm25Index {

    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private final List<Map<String, Integer>> termCounts = new ArrayList<>();
    private final int[] lengths;
    private final Map<String, Integer> documentFrequency = new HashMap<>();
    private final double averageLength;

    Bm25Index(List<KnowledgeChunk> chunks) {
        lengths = new int[chunks.size()];
        long total = 0;
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Integer> counts = new HashMap<>();
            for (String token : tokens(chunks.get(i).text())) {
                counts.merge(token, 1, Integer::sum);
                lengths[i]++;
            }
            counts.keySet().forEach(t -> documentFrequency.merge(t, 1, Integer::sum));
            termCounts.add(counts);
            total += lengths[i];
        }
        averageLength = chunks.isEmpty() ? 1 : Math.max(1, (double) total / chunks.size());
    }

    static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    /** A chunk position with its score. */
    record Scored(int index, double score) {}

    /**
     * Ranks chunks for a query; chunks sharing no term with it are left out.
     *
     * @param query the query
     * @param limit most results
     * @return positions in the pack's chunk list, best first
     */
    List<Scored> search(String query, int limit) {
        List<String> terms = tokens(query).stream().distinct().toList();
        List<Scored> scored = new ArrayList<>();
        int documents = termCounts.size();
        for (int i = 0; i < documents; i++) {
            double score = 0;
            for (String term : terms) {
                Integer tf = termCounts.get(i).get(term);
                if (tf == null) {
                    continue;
                }
                int df = documentFrequency.get(term);
                double idf = Math.log(1 + (documents - df + 0.5) / (df + 0.5));
                score += idf * tf * (K1 + 1) / (tf + K1 * (1 - B + B * lengths[i] / averageLength));
            }
            if (score > 0) {
                scored.add(new Scored(i, score));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparingInt(Scored::index));
        return scored.size() > limit ? scored.subList(0, limit) : scored;
    }
}
