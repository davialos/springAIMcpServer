package com.springaimcpservercommon.ai.knowledge;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An immutable set of chunks under one name.
 *
 * @param name           pack name, {@code ^[a-z0-9][a-z0-9-]{0,63}$}
 * @param embeddingModel identifier of the embedding model the vectors came from (for example
 *                       {@code openai:text-embedding-3-small}), or {@code null} for a lexical-only pack
 * @param dimensions     vector length, 0 when there are no vectors
 * @param chunks         the chunks; all have a vector of {@code dimensions} floats, or none has
 */
public record KnowledgePack(String name, @Nullable String embeddingModel, int dimensions,
                            List<KnowledgeChunk> chunks) {

    /** Allowed pack names. */
    public static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

    /** Validates the components. */
    public KnowledgePack {
        Objects.requireNonNull(name, "name");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("pack name must match " + NAME.pattern() + ": " + name);
        }
        chunks = List.copyOf(chunks);
        boolean vectors = !chunks.isEmpty() && chunks.getFirst().vector() != null;
        for (KnowledgeChunk chunk : chunks) {
            float[] v = chunk.vector();
            if ((v != null) != vectors) {
                throw new IllegalArgumentException("either every chunk has a vector or none does");
            }
            if (v != null && v.length != dimensions) {
                throw new IllegalArgumentException("chunk " + chunk.id() + " has " + v.length
                        + " dimensions, the pack says " + dimensions);
            }
        }
        if (vectors && (embeddingModel == null || embeddingModel.isBlank())) {
            throw new IllegalArgumentException("a pack with vectors must name its embedding model");
        }
    }

    /** @return {@code true} when the chunks carry embeddings */
    public boolean hasVectors() {
        return !chunks.isEmpty() && chunks.getFirst().vector() != null;
    }
}
