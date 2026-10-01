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

    private record Loaded(KnowledgePack pack, Bm25Index bm25) {}

    private final QueryEmbedder embedder;
    private final ClassLoader classLoader;
    private final Map<String, Resource> resources = new HashMap<>();
    private final Map<String, Optional<Loaded>> loaded = new ConcurrentHashMap<>();

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
        this.embedder = new QueryEmbedder(embeddings, embeddingModelId);
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
        Loaded found = load(pack).orElse(null);
        if (found == null) {
            return List.of();
        }
        IndexedPack indexed = new IndexedPack(found.pack(), found.bm25());
        return HybridSearch.search(indexed, query, Math.max(1, Math.min(50, topK)), minSimilarity,
                embedder.embed(found.pack(), query));
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
}
