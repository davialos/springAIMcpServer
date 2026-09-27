package com.springaimcpservercommon.core.catalog;

/**
 * Port holding the current effective catalog (LLD-03 §2). The default implementation is
 * {@link SwappableMetadataRegistry}; hosts may replace it ({@code @ConditionalOnMissingBean} in autoconfigure).
 */
public interface MetadataRegistry {

    /**
     * Returns the current immutable snapshot; never {@code null}, never blocks.
     *
     * @return the current effective catalog
     */
    EffectiveCatalog current();
}
