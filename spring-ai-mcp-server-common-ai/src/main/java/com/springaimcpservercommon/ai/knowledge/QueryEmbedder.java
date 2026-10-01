package com.springaimcpservercommon.ai.knowledge;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Embeds search queries for packs, but only with the model the pack's vectors were made with. Every reason not to
 * (no model, another model id, other dimensions, provider error) answers {@code null} so the caller falls back to
 * keyword search; the first occurrence per pack is logged.
 */
final class QueryEmbedder {

    private static final Logger LOG = LoggerFactory.getLogger(QueryEmbedder.class);

    private final Supplier<@Nullable EmbeddingModel> embeddings;
    private final @Nullable String modelId;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    QueryEmbedder(Supplier<@Nullable EmbeddingModel> embeddings, @Nullable String modelId) {
        this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
        this.modelId = modelId;
    }

    @Nullable EmbeddingModel model() {
        return embeddings.get();
    }

    @Nullable String modelId() {
        return modelId;
    }

    /** @return the query vector, or {@code null} when the pack must be searched by keywords only */
    float @Nullable [] embed(KnowledgePack pack, String query) {
        if (!pack.hasVectors()) {
            return null;
        }
        EmbeddingModel model = embeddings.get();
        if (model == null || modelId == null || !modelId.equals(pack.embeddingModel())) {
            if (warned.add(pack.name())) {
                LOG.warn("Knowledge pack '{}' has embeddings from '{}' but the runtime embedding model is '{}': "
                        + "using keyword search only", pack.name(), pack.embeddingModel(),
                        model == null ? "not available" : modelId);
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
}
