package com.springaimcpservercommon.query.ast;

/**
 * Pagination settings for a published {@link QueryDefinition} (LLD-05 §5a).
 *
 * <p>The effective page size for any execution is:
 * {@code min(globalMaxRows, entityMaxLimit, pageSpec.maxSize(), callerRequestedSize)}.
 *
 * @param defaultSize    default page size returned when the caller does not specify one (1–{@code maxSize})
 * @param maxSize        hard cap per request; executions request {@code maxSize + 1} rows to detect {@code hasMore}
 * @param keysetEnabled  when {@code true}, prefer keyset pagination (a sort spec is required)
 */
public record PageSpec(int defaultSize, int maxSize, boolean keysetEnabled) {

    /** Default spec used when none is configured: 20 default, 200 max, keyset enabled. */
    public static final PageSpec DEFAULT = new PageSpec(20, 200, true);

    /** Validates the bounds. */
    public PageSpec {
        if (defaultSize < 1) {
            throw new IllegalArgumentException("defaultSize must be >= 1, was " + defaultSize);
        }
        if (maxSize < defaultSize) {
            throw new IllegalArgumentException("maxSize (" + maxSize + ") must be >= defaultSize (" + defaultSize + ")");
        }
    }

    /**
     * Clamps a caller-requested size to this spec's bounds.
     *
     * @param requested caller-requested page size (0 or negative uses the default)
     * @return effective page size in range [1, maxSize]
     */
    public int effectiveSize(int requested) {
        if (requested <= 0) {
            return defaultSize;
        }
        return Math.min(requested, maxSize);
    }
}
