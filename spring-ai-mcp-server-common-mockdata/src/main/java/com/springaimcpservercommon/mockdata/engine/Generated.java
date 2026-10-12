package com.springaimcpservercommon.mockdata.engine;

import java.util.List;

/**
 * An instance together with the fields that are only placeholders.
 *
 * @param value the instance
 * @param gaps  fields that need a better value (empty = complete)
 * @param <T>   the type
 */
public record Generated<T>(T value, List<FieldGap> gaps) {

    /** Defensive copy. */
    public Generated {
        gaps = List.copyOf(gaps);
    }

    /**
     * Whether every field got a real value.
     *
     * @return true when there are no gaps
     */
    public boolean complete() {
        return gaps.isEmpty();
    }
}
