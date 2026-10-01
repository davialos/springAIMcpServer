package com.springaimcpservercommon.ai.knowledge;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Serves the packs of several stores as one; the first store that has a pack answers for it. */
public final class CompositeKnowledgeStore implements KnowledgeStore {

    private final List<KnowledgeStore> stores;

    /**
     * Creates the composite.
     *
     * @param stores the stores, in order of precedence
     */
    public CompositeKnowledgeStore(List<KnowledgeStore> stores) {
        this.stores = List.copyOf(stores);
    }

    @Override
    public Set<String> packs() {
        Set<String> all = new TreeSet<>();
        stores.forEach(s -> all.addAll(s.packs()));
        return all;
    }

    @Override
    public Optional<KnowledgePack> pack(String name) {
        return stores.stream().filter(s -> s.packs().contains(name)).findFirst().flatMap(s -> s.pack(name));
    }

    @Override
    public List<KnowledgeHit> search(String pack, String query, int topK, double minSimilarity) {
        return stores.stream().filter(s -> s.packs().contains(pack)).findFirst()
                .map(s -> s.search(pack, query, topK, minSimilarity)).orElse(List.of());
    }
}
