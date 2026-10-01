package com.springaimcpservercommon.autoconfigure;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the bundled knowledge packs (ADR-0022), bound under {@code dynamic.ai.agent.knowledge}. Kept apart
 * from {@link DaiProperties} so the two can evolve independently.
 *
 * @param enabled           serve knowledge packs to agents and host code (default on; nothing happens without packs)
 * @param embeddingModelId  identifier of the application's {@code EmbeddingModel}, for example
 *                          {@code openai:text-embedding-3-small}. Pack vectors are used only when it equals the id the
 *                          pack was built with; unset means keyword search only
 * @param maxContextChars   most characters of retrieved text added to one prompt (500..100000)
 * @param index             the build-time index runner
 */
@ConfigurationProperties(prefix = "dynamic.ai.agent.knowledge")
public record DaiKnowledgeProperties(
        @DefaultValue("true") boolean enabled,
        @Nullable String embeddingModelId,
        @DefaultValue("6000") int maxContextChars,
        @DefaultValue Index index) {

    /** Validates the settings. */
    public DaiKnowledgeProperties {
        if (maxContextChars < 500 || maxContextChars > 100_000) {
            throw new IllegalArgumentException("dynamic.ai.agent.knowledge.max-context-chars must be 500..100000");
        }
    }

    /**
     * Runs the indexer when the application starts, to (re)build a pack file in the codebase. Off by default; a
     * developer or build step turns it on:
     * {@code -Ddynamic.ai.agent.knowledge.index.enabled=true -Ddynamic.ai.agent.knowledge.index.source-dir=...}
     *
     * @param enabled        run the indexer at startup
     * @param sourceDir      directory of {@code .md}/{@code .txt} documents
     * @param outputFile     pack file to write, normally
     *                       {@code src/main/resources/dynamic-ai/knowledge/<pack>/index.jsonl}
     * @param pack           pack name
     * @param maxChunkChars  largest chunk (200..20000)
     * @param exitWhenDone   stop the application after indexing
     */
    public record Index(
            @DefaultValue("false") boolean enabled,
            @Nullable String sourceDir,
            @Nullable String outputFile,
            @Nullable String pack,
            @DefaultValue("1200") int maxChunkChars,
            @DefaultValue("false") boolean exitWhenDone) {}
}
