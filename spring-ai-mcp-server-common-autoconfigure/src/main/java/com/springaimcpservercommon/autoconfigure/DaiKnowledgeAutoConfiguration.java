package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.knowledge.ClasspathKnowledgeStore;
import com.springaimcpservercommon.ai.knowledge.KnowledgeIndexer;
import com.springaimcpservercommon.ai.knowledge.KnowledgeStore;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

/**
 * Bundled knowledge packs (ADR-0022): the {@link KnowledgeStore} over the packs on the class path, and the opt-in
 * runner that builds a pack file from the host's documents using the host's own {@code EmbeddingModel}.
 */
@NullMarked
@AutoConfiguration
@ConditionalOnProperty(prefix = "dynamic.ai.agent", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "dynamic.ai.agent.knowledge", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@ConditionalOnClass(KnowledgeStore.class)
@EnableConfigurationProperties(DaiKnowledgeProperties.class)
public class DaiKnowledgeAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(DaiKnowledgeAutoConfiguration.class);

    /**
     * The packs of the application's JAR. The embedding model is looked up per search, so a model bean defined
     * late is still found.
     *
     * @param embeddings the application's embedding model, when it has one
     * @param props      knowledge settings
     * @return the store
     */
    @Bean
    @ConditionalOnMissingBean(KnowledgeStore.class)
    public KnowledgeStore knowledgeStore(ObjectProvider<EmbeddingModel> embeddings, DaiKnowledgeProperties props) {
        return new ClasspathKnowledgeStore(embeddings::getIfUnique, props.embeddingModelId(),
                Thread.currentThread().getContextClassLoader() != null
                        ? Thread.currentThread().getContextClassLoader()
                        : DaiKnowledgeAutoConfiguration.class.getClassLoader());
    }

    /**
     * Builds a pack file at startup when {@code dynamic.ai.agent.knowledge.index.enabled=true}: the developer's
     * way to turn documents into the {@code index.jsonl} that is committed and bundled by the build.
     *
     * @param embeddings the application's embedding model; without one the pack is keyword-only
     * @param props      knowledge settings
     * @param context    the application context (to stop it when asked)
     * @return the runner
     */
    @Bean
    @ConditionalOnProperty(prefix = "dynamic.ai.agent.knowledge.index", name = "enabled", havingValue = "true")
    public ApplicationRunner knowledgeIndexRunner(ObjectProvider<EmbeddingModel> embeddings,
                                                  DaiKnowledgeProperties props, ConfigurableApplicationContext context) {
        return (ApplicationArguments args) -> {
            DaiKnowledgeProperties.Index index = props.index();
            if (index.sourceDir() == null || index.outputFile() == null || index.pack() == null) {
                throw new IllegalStateException("dynamic.ai.agent.knowledge.index needs source-dir, output-file "
                        + "and pack");
            }
            EmbeddingModel model = embeddings.getIfUnique();
            if (model != null && props.embeddingModelId() == null) {
                throw new IllegalStateException("set dynamic.ai.agent.knowledge.embedding-model-id to name the "
                        + "embedding model the vectors are made with");
            }
            KnowledgeIndexer.Result result = new KnowledgeIndexer(index.maxChunkChars()).index(
                    Path.of(index.sourceDir()), Path.of(index.outputFile()), index.pack(), model,
                    props.embeddingModelId());
            LOG.info("Knowledge pack '{}' written to {}: {} chunks ({} embedded, {} reused){}", index.pack(),
                    index.outputFile(), result.chunks(), result.embedded(), result.reused(),
                    model == null ? ", keyword-only" : "");
            if (index.exitWhenDone()) {
                new Thread(() -> System.exit(SpringApplication.exit(context))).start();
            }
        };
    }
}
