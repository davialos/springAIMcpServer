package com.springaimcpservercommon.ai.knowledge;

/** A pack with its keyword index, ready to search. */
record IndexedPack(KnowledgePack pack, Bm25Index bm25) {

    static IndexedPack of(KnowledgePack pack) {
        return new IndexedPack(pack, new Bm25Index(pack.chunks()));
    }
}
