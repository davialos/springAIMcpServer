package com.springaimcpservercommon.ai.agent;

import com.springaimcpservercommon.ai.knowledge.KnowledgePack;

import java.util.Objects;

/**
 * A knowledge pack an agent draws context from on every turn (ADR-0022).
 *
 * @param pack          pack name
 * @param topK          most chunks added to the prompt from this pack, 1..10
 * @param minSimilarity least cosine similarity (0..1) for an embedding hit; keyword hits are not filtered
 */
public record KnowledgeRef(String pack, int topK, double minSimilarity) {

    /** Validates the components. */
    public KnowledgeRef {
        Objects.requireNonNull(pack, "pack");
        if (!KnowledgePack.NAME.matcher(pack).matches()) {
            throw new IllegalArgumentException("pack name must match " + KnowledgePack.NAME.pattern());
        }
        if (topK < 1 || topK > 10) {
            throw new IllegalArgumentException("topK must be 1..10");
        }
        if (minSimilarity < 0 || minSimilarity > 1) {
            throw new IllegalArgumentException("minSimilarity must be 0..1");
        }
    }
}
