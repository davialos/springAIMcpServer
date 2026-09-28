package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.catalog.MetadataRegistry;
import com.springaimcpservercommon.persistence.config.ConfigStore;
import org.jspecify.annotations.NullMarked;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Auto-configuration for the admin control-plane API (LLD-08).
 *
 * <p>Registers:
 * <ul>
 *   <li>{@link CatalogAdminController} — catalog read endpoints under
 *       {@code /dynamic-ai/admin/api/v1/catalog}</li>
 *   <li>{@link ResourceAdminController} — resource lifecycle endpoints under
 *       {@code /dynamic-ai/admin/api/v1/workspaces/{workspaceId}/resources}</li>
 * </ul>
 *
 * <p>Catalog endpoints are registered whenever a {@link MetadataRegistry} bean is present
 * (requires only the core module). Resource endpoints additionally require a {@link ConfigStore}
 * bean (persistence module).
 *
 * <p>Both controllers are {@link ConditionalOnMissingBean} — host applications may replace either.
 */
@AutoConfiguration(after = {DaiCoreAutoConfiguration.class, DaiPersistenceAutoConfiguration.class,
                             DaiWebMvcAutoConfiguration.class})
@ConditionalOnClass(RequestMappingHandlerMapping.class)
@NullMarked
public class DaiAdminAutoConfiguration {

    /**
     * Catalog read controller: exposes the live {@link com.springaimcpservercommon.core.catalog.EffectiveCatalog}
     * over HTTP (LLD-08 §2.1).
     *
     * @param metadataRegistry live effective catalog
     * @return the controller
     */
    @Bean
    @ConditionalOnMissingBean(CatalogAdminController.class)
    @ConditionalOnBean(MetadataRegistry.class)
    public CatalogAdminController catalogAdminController(MetadataRegistry metadataRegistry) {
        return new CatalogAdminController(metadataRegistry);
    }

    /**
     * Resource lifecycle controller: create, read, draft, publish, suspend and resume (LLD-08 §2, LLD-09).
     *
     * @param configStore configuration lifecycle store
     * @return the controller
     */
    @Bean
    @ConditionalOnMissingBean(ResourceAdminController.class)
    @ConditionalOnBean(ConfigStore.class)
    public ResourceAdminController resourceAdminController(ConfigStore configStore) {
        return new ResourceAdminController(configStore);
    }
}
