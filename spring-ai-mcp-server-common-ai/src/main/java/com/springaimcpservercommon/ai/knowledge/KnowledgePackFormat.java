package com.springaimcpservercommon.ai.knowledge;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The on-disk form of a pack: JSON Lines, UTF-8. Line 1 is the header
 * {@code {"format":"dai-knowledge/1","name":…,"embeddingModel":…,"dimensions":…,"chunks":n}}, every further line is
 * one chunk {@code {"id":…,"source":…,"ordinal":…,"text":…,"vector":[…]}}. One chunk per line and a fixed field
 * order keep version-control diffs small. The file lives in the host's codebase at
 * {@code src/main/resources/dynamic-ai/knowledge/<pack>/index.jsonl} and is bundled into the JAR by the build.
 */
public final class KnowledgePackFormat {

    /** Format marker of the header line. */
    public static final String FORMAT = "dai-knowledge/1";
    /** Most chunks a pack may hold (guards memory: the whole pack is held in memory). */
    public static final int MAX_CHUNKS = 50_000;

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private KnowledgePackFormat() {
    }

    /**
     * Writes a pack.
     *
     * @param pack the pack
     * @param out  destination (not closed)
     * @throws UncheckedIOException on write failure
     */
    public static void write(KnowledgePack pack, Writer out) {
        try {
            ObjectNode header = MAPPER.createObjectNode();
            header.put("format", FORMAT);
            header.put("name", pack.name());
            if (pack.embeddingModel() != null) {
                header.put("embeddingModel", pack.embeddingModel());
            }
            header.put("dimensions", pack.dimensions());
            header.put("chunks", pack.chunks().size());
            out.write(MAPPER.writeValueAsString(header));
            out.write('\n');
            for (KnowledgeChunk chunk : pack.chunks()) {
                ObjectNode line = MAPPER.createObjectNode();
                line.put("id", chunk.id());
                line.put("source", chunk.source());
                line.put("ordinal", chunk.ordinal());
                line.put("text", chunk.text());
                float[] vector = chunk.vector();
                if (vector != null) {
                    ArrayNode array = line.putArray("vector");
                    for (float f : vector) {
                        array.add(f);
                    }
                }
                out.write(MAPPER.writeValueAsString(line));
                out.write('\n');
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Reads a pack.
     *
     * @param in the stream (not closed)
     * @return the pack
     * @throws IllegalArgumentException when the content is not a valid pack
     * @throws UncheckedIOException     on read failure
     */
    public static KnowledgePack read(InputStream in) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String first = reader.readLine();
            if (first == null) {
                throw new IllegalArgumentException("empty knowledge pack");
            }
            JsonNode header = MAPPER.readTree(first);
            if (!FORMAT.equals(text(header, "format"))) {
                throw new IllegalArgumentException("not a " + FORMAT + " pack");
            }
            String name = required(header, "name");
            String model = text(header, "embeddingModel");
            int dimensions = header.path("dimensions").asInt(0);
            int expected = header.path("chunks").asInt(-1);
            if (expected < 0 || expected > MAX_CHUNKS) {
                throw new IllegalArgumentException("chunk count must be 0.." + MAX_CHUNKS);
            }
            List<KnowledgeChunk> chunks = new ArrayList<>(expected);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                if (chunks.size() >= MAX_CHUNKS) {
                    throw new IllegalArgumentException("more than " + MAX_CHUNKS + " chunks");
                }
                JsonNode node = MAPPER.readTree(line);
                float[] vector = null;
                JsonNode array = node.get("vector");
                if (array != null && array.isArray()) {
                    vector = new float[array.size()];
                    for (int i = 0; i < vector.length; i++) {
                        float value = array.get(i).floatValue();
                        if (Float.isNaN(value) || Float.isInfinite(value)) {
                            throw new IllegalArgumentException("vector of " + text(node, "id") + " is not finite");
                        }
                        vector[i] = value;
                    }
                }
                chunks.add(new KnowledgeChunk(required(node, "id"), required(node, "source"),
                        node.path("ordinal").asInt(0), required(node, "text"), vector));
            }
            if (chunks.size() != expected) {
                throw new IllegalArgumentException("header says " + expected + " chunks, found " + chunks.size());
            }
            return new KnowledgePack(name, model, dimensions, chunks);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalArgumentException("malformed knowledge pack: " + e.getOriginalMessage(), e);
        }
    }

    private static @Nullable String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static String required(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            throw new IllegalArgumentException("missing field '" + field + "'");
        }
        return value;
    }
}
