package com.springaimcpservercommon.core.display;

import java.util.List;

/**
 * Backend-controlled layout of an agent's answer (LLD-06 §8.3): a tree of {@link DisplayNode}s authored as JSON with
 * the agent definition and published through the reviewed config lifecycle (LLD-09). Only the values a template
 * names are displayed; the rest of the answer data never reaches the display tree. Parse with
 * {@link DisplayTemplateParser}.
 *
 * @param version template format version (currently {@link #VERSION})
 * @param blocks  top-level nodes, in display order
 */
public record DisplayTemplate(int version, List<DisplayNode> blocks) {

    /** Current template format version. */
    public static final int VERSION = 1;

    /** Validates and copies. */
    public DisplayTemplate {
        if (version != VERSION) {
            throw new IllegalArgumentException("unsupported display template version " + version);
        }
        blocks = List.copyOf(blocks);
    }
}
