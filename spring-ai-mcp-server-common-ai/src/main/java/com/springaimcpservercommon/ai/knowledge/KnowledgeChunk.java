package com.springaimcpservercommon.ai.knowledge;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * One searchable piece of a knowledge pack.
 *
 * @param id      stable id, {@code <source>#<ordinal>}
 * @param source  the document the chunk came from (path relative to the pack's source directory)
 * @param ordinal position of the chunk in its document, from 0
 * @param text    the chunk text (with its heading trail)
 * @param vector  embedding of {@code text}, or {@code null} for a lexical-only pack
 */
public record KnowledgeChunk(String id, String source, int ordinal, String text, float @Nullable [] vector) {

    /** Validates the components. */
    public KnowledgeChunk {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(text, "text");
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must be >= 0");
        }
    }
}
