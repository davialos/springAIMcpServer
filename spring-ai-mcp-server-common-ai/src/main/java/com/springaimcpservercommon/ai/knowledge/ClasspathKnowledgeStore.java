package com.springaimcpservercommon.ai.knowledge;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Serves the packs found on the class path at {@code dynamic-ai/knowledge/<pack>/index.jsonl}: the files the host
 * keeps in its codebase and its build bundles into the JAR (ADR-0022). Packs are read on first use and then held in
 * memory, immutable.
 *
 * <p>Search is hybrid when it can be: keyword ranking (BM25) always, plus embedding similarity when the pack has
 * vectors, the runtime's embedding model is configured with the same identifier the vectors were made with and its
 * output has the pack's dimensions. The two rankings are merged by reciprocal rank. A query that cannot be embedded
 * (no model, other model, provider error) falls back to keywords alone: the feature degrades, the caller is not
 * failed.
 */
public final class ClasspathKnowledgeStore implements KnowledgeStore {

    private static final Logger LOG = LoggerFactory.getLogger(ClasspathKnowledgeStore.class);

    /** Location pattern of pack files. */
    public static final String LOCATION = "classpath*:dynamic-ai/knowledge/*/index.jsonl";
    private static final int CANDIDATES = 50;
    private static final int RRF_K = 60;

    private record Loaded(KnowledgePack pack, Bm25Index bm25) {}

    private final Supplier<@Nullable EmbeddingModel> embeddings;
    private final @Nullable String embeddingModelId;
    private final ClassLoader classLoader;
    private final Map<String, Resource> resources = new HashMap<>();
    private final Map<String, Optional<Loaded>> loaded = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    /**
     * Creates the store and discovers the pack files (they are not read yet).
     *
     * @param embeddings       the runtime's embedding model, resolved per search (may yield {@code null})
     * @param embeddingModelId identifier the configured model is known by; vectors are used only for packs made
     *                         with the same id; {@code null} means keyword search only
     * @param classLoader      where to look for packs
     */
    public ClasspathKnowledgeStore(Supplier<@Nullable EmbeddingModel> embeddings, @Nullable String embeddingModelId,
                                   ClassLoader classLoader) {
        this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
        this.embeddingModelId = embeddingModelId;
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver(classLoader).getResources(LOCATION)) {
                String url = resource.getURL().toString();
                String[] parts = url.split("/");
                String name = parts[parts.length - 2];
                if (KnowledgePack.NAME.matcher(name).matches()) {
                    resources.putIfAbsent(name, resource);
                }
            }
        } catch (IOException e) {
            LOG.warn("Could not scan the class path for knowledge packs ({})", e.getClass().getSimpleName());
        }
    }

    @Override
    public Set<String> packs() {
        return new TreeSet<>(resources.keySet());
    }

    @Override
    public Optional<KnowledgePack> pack(String name) {
        return load(name).map(Loaded::pack);
    }

    @Override
    public List<KnowledgeHit> search(String pack, String query, int topK, double minSimilarity) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int limit = Math.max(1, Math.min(50, topK));
        Loaded loaded = load(pack).orElse(null);
        if (loaded == null) {
            return List.of();
        }
        List<KnowledgeChunk> chunks = loaded.pack().chunks();
        Map<Integer, Double> fused = new HashMap<>();
        List<Bm25Index.Scored> keyword = loaded.bm25().search(query, CANDIDATES);
        for (int rank = 0; rank < keyword.size(); rank++) {
            fused.merge(keyword.get(rank).index(), 1.0 / (RRF_K + rank + 1), Double::sum);
        }
        Map<Integer, Double> similarity = new HashMap<>();
        float[] queryVector = embedQuery(loaded.pack(), query);
        if (queryVector != null) {
            List<int[]> ranked = new ArrayList<>();
            double[] scores = new double[chunks.size()];
            for (int i = 0; i < chunks.size(); i++) {
                scores[i] = cosine(queryVector, Objects.requireNonNull(chunks.get(i).vector()));
                if (scores[i] >= minSimilarity) {
                    ranked.add(new int[] {i});
                }
            }
            ranked.sort(Comparator.<int[]>comparingDouble(a -> scores[a[0]]).reversed()
                    .thenComparingInt(a -> a[0]));
            for (int rank = 0; rank < ranked.size() && rank < CANDIDATES; rank++) {
                int index = ranked.get(rank)[0];
                fused.merge(index, 1.0 / (RRF_K + rank + 1), Double::sum);
                similarity.put(index, scores[index]);
            }
        }
        return fused.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(e -> new KnowledgeHit(pack, chunks.get(e.getKey()), e.getValue()))
                .toList();
    }

    private Optional<Loaded> load(String name) {
        Resource resource = resources.get(name);
        if (resource == null) {
            return Optional.empty();
        }
        return loaded.computeIfAbsent(name, n -> {
            try (InputStream in = resource.getInputStream()) {
                KnowledgePack pack = KnowledgePackFormat.read(in);
                if (!pack.name().equals(n)) {
                    throw new IllegalArgumentException("pack file says '" + pack.name() + "' but lives in '" + n + "'");
                }
                return Optional.of(new Loaded(pack, new Bm25Index(pack.chunks())));
            } catch (IOException | RuntimeException e) {
                LOG.warn("Knowledge pack '{}' is unusable: {}", n, e.getMessage());
                return Optional.empty();
            }
        });
    }

    private float @Nullable [] embedQuery(KnowledgePack pack, String query) {
        if (!pack.hasVectors()) {
            return null;
        }
        EmbeddingModel model = embeddings.get();
        if (model == null || embeddingModelId == null || !embeddingModelId.equals(pack.embeddingModel())) {
            if (warned.add(pack.name())) {
                LOG.warn("Knowledge pack '{}' has embeddings from '{}' but the runtime embedding model is '{}': "
                        + "using keyword search only", pack.name(), pack.embeddingModel(),
                        model == null ? "not available" : embeddingModelId);
            }
            return null;
        }
        try {
            float[] vector = model.embed(query);
            if (vector.length != pack.dimensions()) {
                if (warned.add(pack.name() + "#dim")) {
                    LOG.warn("Embedding model answered {} dimensions, pack '{}' has {}: using keyword search only",
                            vector.length, pack.name(), pack.dimensions());
                }
                return null;
            }
            return vector;
        } catch (RuntimeException e) {
            LOG.warn("Embedding the query failed ({}): using keyword search only", e.getClass().getSimpleName());
            return null;
        }
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
