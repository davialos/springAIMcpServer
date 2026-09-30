package com.springaimcpservercommon.ai.knowledge;

import java.util.Objects;

/**
 * A search result.
 *
 * @param pack  the pack searched
 * @param chunk the matching chunk
 * @param score relevance, higher is better; only comparable within one search
 */
public record KnowledgeHit(String pack, KnowledgeChunk chunk, double score) {

    /** Validates the components. */
    public KnowledgeHit {
        Objects.requireNonNull(pack, "pack");
        Objects.requireNonNull(chunk, "chunk");
    }
}
