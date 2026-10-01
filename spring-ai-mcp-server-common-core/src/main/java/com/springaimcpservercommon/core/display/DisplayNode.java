package com.springaimcpservercommon.core.display;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/** A node of a {@link DisplayTemplate}. */
public sealed interface DisplayNode
        permits DisplayNode.Text, DisplayNode.Fields, DisplayNode.Table, DisplayNode.Section {

    /**
     * The answer's prose (the text around any JSON data), redacted.
     *
     * @param title optional heading
     */
    record Text(@Nullable String title) implements DisplayNode {
    }

    /**
     * Label/value pairs read from one object of the answer data.
     *
     * @param title  optional heading
     * @param source dot path to the object; {@code null} for the data root
     * @param fields the values to show, in order; nothing else of the object is shown
     */
    record Fields(@Nullable String title, @Nullable String source, List<FieldSpec> fields) implements DisplayNode {
        /** Copies the fields. */
        public Fields {
            fields = List.copyOf(fields);
        }
    }

    /**
     * Rows read from an array of the answer data.
     *
     * @param title   optional heading
     * @param source  dot path to the array; {@code null} for the data root
     * @param columns the columns, in order; nothing else of a row is shown
     * @param maxRows most rows shown; the block reports the total and whether it was cut
     */
    record Table(@Nullable String title, @Nullable String source, List<FieldSpec> columns, int maxRows)
            implements DisplayNode {
        /** Validates and copies. */
        public Table {
            columns = List.copyOf(columns);
            if (maxRows < 1) {
                throw new IllegalArgumentException("maxRows must be >= 1");
            }
        }
    }

    /**
     * A titled group of nodes.
     *
     * @param title    heading
     * @param children nested nodes
     */
    record Section(String title, List<DisplayNode> children) implements DisplayNode {
        /** Validates and copies. */
        public Section {
            Objects.requireNonNull(title, "title");
            children = List.copyOf(children);
        }
    }
}
