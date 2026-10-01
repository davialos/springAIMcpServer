package com.springaimcpservercommon.core.guard;

import java.util.Objects;

/**
 * A range of text a {@link PiiDetector} considers personal or secret data. Carries positions, never the value.
 *
 * @param type  kind of data
 * @param start index of the first character (inclusive)
 * @param end   index after the last character (exclusive), greater than {@code start}
 */
public record PiiMatch(PiiType type, int start, int end) {

    /** Validates the range. */
    public PiiMatch {
        Objects.requireNonNull(type, "type");
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("invalid range [" + start + ", " + end + ")");
        }
    }

    /**
     * Length of the range.
     *
     * @return number of characters
     */
    public int length() {
        return end - start;
    }
}
