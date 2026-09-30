package com.springaimcpservercommon.ai.knowledge;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Read access to the knowledge packs an application ships. Immutable content, so any node answers the same and
 * nothing needs to be shared between replicas (ADR-0021). Host code can inject it and search directly.
 */
public interface KnowledgeStore {

    /** @return the names of the available packs */
    Set<String> packs();

    /**
     * Returns a pack.
     *
     * @param name pack name
     * @return the pack, if it exists and could be read
     */
    Optional<KnowledgePack> pack(String name);

    /**
     * Searches a pack. Never throws for an unknown pack or an unusable embedding model: it answers with what it
     * can (lexical search) or with nothing.
     *
     * @param pack          pack name
     * @param query         the question or keywords
     * @param topK          most hits wanted, 1..50
     * @param minSimilarity least cosine similarity (0..1) for a hit found by embedding search; ignored for keyword
     *                      hits
     * @return hits, best first
     */
    List<KnowledgeHit> search(String pack, String query, int topK, double minSimilarity);

    /**
     * Searches a pack with no similarity floor.
     *
     * @param pack  pack name
     * @param query the question or keywords
     * @param topK  most hits wanted
     * @return hits, best first
     */
    default List<KnowledgeHit> search(String pack, String query, int topK) {
        return search(pack, query, topK, 0.0);
    }
}
