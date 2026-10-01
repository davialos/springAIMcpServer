package com.springaimcpservercommon.ai.knowledge;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.embedding.EmbeddingModel;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Builds a pack file from a directory of {@code .md} / {@code .txt} documents. Run it by hand or from the host's
 * build, commit the resulting {@code index.jsonl}; the build then bundles it into the JAR (ADR-0022).
 *
 * <p>Incremental: a chunk whose text is unchanged keeps the vector of the existing file, so re-indexing after a
 * small edit embeds only what changed (embedding calls cost money). Without an {@link EmbeddingModel} the pack is
 * lexical-only, which needs no key and works in any CI.
 */
public final class KnowledgeIndexer {

    /** Chunks embedded per call. */
    static final int BATCH = 32;

    private final TextChunker chunker;

    /**
     * Creates an indexer.
     *
     * @param maxChunkChars largest chunk size in characters
     */
    public KnowledgeIndexer(int maxChunkChars) {
        this.chunker = new TextChunker(maxChunkChars);
    }

    /**
     * What an indexing run did.
     *
     * @param chunks   chunks in the pack
     * @param embedded chunks embedded by this run
     * @param reused   chunks whose vector came from the previous file
     */
    public record Result(int chunks, int embedded, int reused) {}

    /**
     * Indexes a directory.
     *
     * @param sourceDir      documents ({@code .md}, {@code .txt}; sub-directories included)
     * @param out            the pack file to write (replaced atomically); an existing file is used for reuse
     * @param pack           pack name
     * @param model          embedding model, or {@code null} for a lexical-only pack
     * @param embeddingModel identifier stored with the vectors (required with a model), for example
     *                       {@code openai:text-embedding-3-small}; the runtime only uses the vectors when its
     *                       configured id is equal
     * @return what was done
     */
    public Result index(Path sourceDir, Path out, String pack, @Nullable EmbeddingModel model,
                        @Nullable String embeddingModel) {
        Objects.requireNonNull(sourceDir, "sourceDir");
        Objects.requireNonNull(out, "out");
        if (model != null && (embeddingModel == null || embeddingModel.isBlank())) {
            throw new IllegalArgumentException("embeddingModel id is required when an embedding model is used");
        }
        Map<String, float[]> known = model == null ? Map.of() : existingVectors(out, embeddingModel);
        List<String> ids = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        List<Integer> ordinals = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceDir)) {
            List<Path> documents = files.filter(Files::isRegularFile).filter(KnowledgeIndexer::isDocument)
                    .sorted().toList();
            for (Path file : documents) {
                String source = sourceDir.relativize(file).toString().replace('\\', '/');
                List<String> chunks = chunker.chunk(Files.readString(file, StandardCharsets.UTF_8));
                for (int i = 0; i < chunks.size(); i++) {
                    ids.add(source + "#" + i);
                    sources.add(source);
                    ordinals.add(i);
                    texts.add(chunks.get(i));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        float[][] vectors = new float[texts.size()][];
        int embedded = 0;
        int reused = 0;
        if (model != null) {
            List<Integer> missing = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                float[] previous = known.get(hash(texts.get(i)));
                if (previous != null) {
                    vectors[i] = previous;
                    reused++;
                } else {
                    missing.add(i);
                }
            }
            for (int from = 0; from < missing.size(); from += BATCH) {
                List<Integer> batch = missing.subList(from, Math.min(missing.size(), from + BATCH));
                List<float[]> result = model.embed(batch.stream().map(texts::get).toList());
                if (result.size() != batch.size()) {
                    throw new IllegalStateException("the embedding model returned " + result.size()
                            + " vectors for " + batch.size() + " texts");
                }
                for (int i = 0; i < batch.size(); i++) {
                    vectors[batch.get(i)] = result.get(i);
                    embedded++;
                }
            }
        }
        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            chunks.add(new KnowledgeChunk(ids.get(i), sources.get(i), ordinals.get(i), texts.get(i), vectors[i]));
        }
        int dimensions = chunks.isEmpty() || chunks.getFirst().vector() == null
                ? 0 : chunks.getFirst().vector().length;
        KnowledgePack result = new KnowledgePack(pack, model == null ? null : embeddingModel, dimensions, chunks);
        writeAtomically(result, out);
        return new Result(chunks.size(), embedded, reused);
    }

    private static Map<String, float[]> existingVectors(Path out, @Nullable String embeddingModel) {
        if (!Files.isRegularFile(out)) {
            return Map.of();
        }
        try (InputStream in = Files.newInputStream(out)) {
            KnowledgePack old = KnowledgePackFormat.read(in);
            if (!Objects.equals(old.embeddingModel(), embeddingModel)) {
                return Map.of();
            }
            Map<String, float[]> byHash = new HashMap<>();
            for (KnowledgeChunk chunk : old.chunks()) {
                if (chunk.vector() != null) {
                    byHash.put(hash(chunk.text()), chunk.vector());
                }
            }
            return byHash;
        } catch (IOException | RuntimeException e) {
            return Map.of(); // an unreadable old file is simply rebuilt
        }
    }

    private static void writeAtomically(KnowledgePack pack, Path out) {
        try {
            Path parent = out.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path tmp = Files.createTempFile(parent, "knowledge", ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                KnowledgePackFormat.write(pack, writer);
            }
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isDocument(Path file) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".md") || name.endsWith(".txt");
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Command line for lexical-only packs (no key needed, fit for CI):
     * {@code KnowledgeIndexer <sourceDir> <index.jsonl> <packName>}. Packs with embeddings are built by the host
     * application itself (it holds the {@link EmbeddingModel}); see the starter's knowledge index runner.
     *
     * @param args source directory, output file, pack name
     */
    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println("usage: KnowledgeIndexer <sourceDir> <index.jsonl> <packName>");
            System.exit(2);
        }
        Result result = new KnowledgeIndexer(1200).index(Path.of(args[0]), Path.of(args[1]), args[2], null, null);
        System.out.println("indexed " + result.chunks() + " chunks into " + args[1]);
    }
}
