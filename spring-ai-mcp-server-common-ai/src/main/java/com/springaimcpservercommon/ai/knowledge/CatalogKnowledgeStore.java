package com.springaimcpservercommon.ai.knowledge;

import com.springaimcpservercommon.core.catalog.EffectiveAttribute;
import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.catalog.EffectiveEntity;
import com.springaimcpservercommon.core.catalog.EffectiveOperation;
import com.springaimcpservercommon.core.catalog.ParamDescriptor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A pack named {@value #PACK} built from the live effective catalog: one chunk per enabled operation (its intent
 * and every parameter with the plain-English {@code details} and {@code examples} the code author wrote) and one per
 * enabled entity (its exposable, non-sensitive attributes and their meanings). An agent that lists the pack in its
 * {@code knowledge} gets the relevant part of that text in its prompt, which helps it tell whether a value or a
 * parameter is the right one.
 *
 * <p>Only what the model may already see goes in: disabled elements, sensitive attributes and the example values of
 * sensitive parameters are left out. The pack is rebuilt when the catalog generation changes; embeddings are made
 * lazily, once per generation, and only when an embedding model and its id are configured (otherwise, or when
 * embedding fails, search is by keywords). It describes the whole catalog to every caller of an agent that uses it,
 * so it is opt-in per agent.
 */
public final class CatalogKnowledgeStore implements KnowledgeStore {

    /** Name of the pack. */
    public static final String PACK = "catalog";

    private static final Logger LOG = LoggerFactory.getLogger(CatalogKnowledgeStore.class);
    private static final int MAX_EMBEDDED_CHUNKS = 500;
    private static final int BATCH = 32;

    private record Built(long generation, IndexedPack keyword, @Nullable IndexedPack withVectors, boolean vectorsTried) {}

    private final Supplier<EffectiveCatalog> catalog;
    private final QueryEmbedder embedder;
    private volatile @Nullable Built built;

    /**
     * Creates the store.
     *
     * @param catalog          the current effective catalog
     * @param embeddings       the application's embedding model, when it has one
     * @param embeddingModelId identifier of that model, or {@code null} for keyword search only
     */
    public CatalogKnowledgeStore(Supplier<EffectiveCatalog> catalog,
                                 Supplier<@Nullable EmbeddingModel> embeddings, @Nullable String embeddingModelId) {
        this.catalog = catalog;
        this.embedder = new QueryEmbedder(embeddings, embeddingModelId);
    }

    @Override
    public Set<String> packs() {
        return Set.of(PACK);
    }

    @Override
    public Optional<KnowledgePack> pack(String name) {
        return PACK.equals(name) ? Optional.of(current().pack()) : Optional.empty();
    }

    @Override
    public List<KnowledgeHit> search(String pack, String query, int topK, double minSimilarity) {
        if (!PACK.equals(pack) || query == null || query.isBlank()) {
            return List.of();
        }
        IndexedPack indexed = current();
        return HybridSearch.search(indexed, query, Math.max(1, Math.min(50, topK)), minSimilarity,
                embedder.embed(indexed.pack(), query));
    }

    private IndexedPack current() {
        EffectiveCatalog effective = catalog.get();
        Built b = built;
        if (b == null || b.generation() != effective.generation()) {
            synchronized (this) {
                b = built;
                if (b == null || b.generation() != effective.generation()) {
                    b = new Built(effective.generation(), IndexedPack.of(buildPack(effective)), null, false);
                    built = b;
                }
            }
        }
        if (!b.vectorsTried() && embedder.model() != null && embedder.modelId() != null) {
            synchronized (this) {
                b = built;
                if (b != null && !b.vectorsTried()) {
                    b = new Built(b.generation(), b.keyword(), embedAll(b.keyword().pack()), true);
                    built = b;
                }
            }
        }
        return b != null && b.withVectors() != null ? b.withVectors() : b.keyword();
    }

    private @Nullable IndexedPack embedAll(KnowledgePack keywordPack) {
        EmbeddingModel model = embedder.model();
        String id = embedder.modelId();
        List<KnowledgeChunk> chunks = keywordPack.chunks();
        if (model == null || id == null || chunks.isEmpty() || chunks.size() > MAX_EMBEDDED_CHUNKS) {
            return null;
        }
        try {
            List<KnowledgeChunk> embedded = new ArrayList<>();
            for (int from = 0; from < chunks.size(); from += BATCH) {
                List<KnowledgeChunk> batch = chunks.subList(from, Math.min(chunks.size(), from + BATCH));
                List<float[]> vectors = model.embed(batch.stream().map(KnowledgeChunk::text).toList());
                if (vectors.size() != batch.size()) {
                    return null;
                }
                for (int i = 0; i < batch.size(); i++) {
                    KnowledgeChunk c = batch.get(i);
                    embedded.add(new KnowledgeChunk(c.id(), c.source(), c.ordinal(), c.text(), vectors.get(i)));
                }
            }
            return IndexedPack.of(new KnowledgePack(PACK, id, embedded.getFirst().vector().length, embedded));
        } catch (RuntimeException e) {
            LOG.warn("Embedding the catalog failed ({}): the catalog pack is searched by keywords",
                    e.getClass().getSimpleName());
            return null;
        }
    }

    /** Builds the keyword-only pack for a catalog generation. */
    static KnowledgePack buildPack(EffectiveCatalog effective) {
        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (EffectiveOperation op : effective.enabledOperations()) {
            chunks.add(chunk("op:" + op.toolName(), op.toolName(), operationText(op), chunks.size()));
        }
        for (EffectiveEntity entity : effective.entities().values()) {
            if (entity.enabled()) {
                chunks.add(chunk("entity:" + entity.name(), entity.name(), entityText(entity), chunks.size()));
            }
        }
        return new KnowledgePack(PACK, null, 0, chunks);
    }

    private static KnowledgeChunk chunk(String id, String source, String text, int ordinal) {
        return new KnowledgeChunk(id, source, ordinal, text, null);
    }

    static String operationText(EffectiveOperation op) {
        StringBuilder text = new StringBuilder("Tool ").append(op.toolName()).append(": ").append(op.description())
                .append(op.readOnly() ? " (reads data only)" : " (changes data: proposed for review, never run by the AI)");
        if (!op.params().isEmpty()) {
            text.append("\nParameters:");
        }
        for (ParamDescriptor p : op.params()) {
            text.append("\n- ").append(p.name()).append(p.required() ? " (required)" : " (optional)");
            if (p.description() != null) {
                text.append(": ").append(p.description());
            }
            if (p.details() != null) {
                text.append(' ').append(p.details());
            }
            if (!p.examples().isEmpty()) {
                text.append(" Examples: ").append(String.join("; ", p.examples())).append('.');
            }
        }
        return text.toString();
    }

    static String entityText(EffectiveEntity entity) {
        StringBuilder text = new StringBuilder("Record type ").append(entity.name()).append(": ")
                .append(entity.description());
        for (EffectiveAttribute a : entity.attributes().values()) {
            if (a.enabled() && a.exposable()) {
                text.append("\n- ").append(a.name()).append(": ").append(a.meaning());
                if (a.rowContext()) {
                    text.append(" (each record carries its own text here, delivered as context with every row)");
                }
            }
        }
        return text.toString();
    }
}
