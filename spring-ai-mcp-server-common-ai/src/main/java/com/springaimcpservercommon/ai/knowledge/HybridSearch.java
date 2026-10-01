package com.springaimcpservercommon.ai.knowledge;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Keyword (BM25) and embedding rankings of one pack merged by reciprocal rank. */
final class HybridSearch {

    private static final int CANDIDATES = 50;
    private static final int RRF_K = 60;

    private HybridSearch() {
    }

    static List<KnowledgeHit> search(IndexedPack indexed, String query, int limit, double minSimilarity,
                                     float @Nullable [] queryVector) {
        String pack = indexed.pack().name();
        List<KnowledgeChunk> chunks = indexed.pack().chunks();
        Map<Integer, Double> fused = new HashMap<>();
        List<Bm25Index.Scored> keyword = indexed.bm25().search(query, CANDIDATES);
        for (int rank = 0; rank < keyword.size(); rank++) {
            fused.merge(keyword.get(rank).index(), 1.0 / (RRF_K + rank + 1), Double::sum);
        }
        if (queryVector != null) {
            double[] scores = new double[chunks.size()];
            List<Integer> ranked = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                scores[i] = cosine(queryVector, Objects.requireNonNull(chunks.get(i).vector()));
                if (scores[i] >= minSimilarity) {
                    ranked.add(i);
                }
            }
            ranked.sort(Comparator.<Integer>comparingDouble(i -> scores[i]).reversed().thenComparingInt(i -> i));
            for (int rank = 0; rank < ranked.size() && rank < CANDIDATES; rank++) {
                fused.merge(ranked.get(rank), 1.0 / (RRF_K + rank + 1), Double::sum);
            }
        }
        return fused.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(e -> new KnowledgeHit(pack, chunks.get(e.getKey()), e.getValue()))
                .toList();
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
